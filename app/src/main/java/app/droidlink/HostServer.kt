package app.droidlink

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * TCP server on the sharing phone. Accepts one viewer at a time after it proves it knows the PIN,
 * streams JPEG frames to it, and hands its touch / button / text commands to [Listener].
 */
class HostServer(
    private val currentPin: () -> String,
    private val listener: Listener
) {
    data class ScreenInfo(val width: Int, val height: Int, val canControl: Boolean)

    interface Listener {
        fun screenInfo(): ScreenInfo
        fun onClientConnected(address: String)
        fun onClientDisconnected()
        fun onGesture(points: FloatArray, durationMs: Long)
        fun onGlobalAction(action: Int)
        fun onText(mode: Int, text: String)
        fun onTooManyBadPins()
    }

    companion object {
        private const val HEARTBEAT_MS = 2000L
        private const val MAX_FAILED_ATTEMPTS = 8
    }

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var session: Session? = null
    private val sessionLock = Any()
    private val failedAttempts = AtomicInteger(0)

    val hasClient: Boolean get() = session != null

    /** Starts listening and returns the port. */
    fun start(): Int {
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(Protocol.DEFAULT_PORT))
            }
        } catch (e: IOException) {
            ServerSocket(0) // default port busy: let the system pick one
        }
        serverSocket = socket
        running = true
        thread(name = "droidlink-accept", isDaemon = true) { acceptLoop(socket) }
        return socket.localPort
    }

    fun stop() {
        running = false
        closeQuietly(serverSocket)
        serverSocket = null
        val s = synchronized(sessionLock) {
            val current = session
            session = null
            current
        }
        s?.close()
    }

    fun publishFrame(jpeg: ByteArray) {
        session?.offerFrame(jpeg)
    }

    fun sendInfo(info: ScreenInfo) {
        session?.offerInfo(info)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            thread(name = "droidlink-client", isDaemon = true) { handleClient(client) }
        }
    }

    private fun handleClient(socket: Socket) {
        var mine: Session? = null
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 10_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))

            if (input.readInt() != Protocol.MAGIC) return
            val version = input.readInt()
            val pin = input.readUTF()
            if (version != Protocol.VERSION) {
                reply(output, Protocol.AUTH_BAD_VERSION)
                return
            }
            if (pin != currentPin()) {
                Thread.sleep(1500) // slow down guessing
                reply(output, Protocol.AUTH_BAD_PIN)
                if (failedAttempts.incrementAndGet() >= MAX_FAILED_ATTEMPTS) {
                    failedAttempts.set(0)
                    listener.onTooManyBadPins()
                }
                return
            }

            val newSession = Session(socket, input, output)
            synchronized(sessionLock) {
                if (session != null || !running) {
                    reply(output, Protocol.AUTH_BUSY)
                    return
                }
                session = newSession
                mine = newSession
            }
            failedAttempts.set(0)

            val info = listener.screenInfo()
            output.writeByte(Protocol.AUTH_OK)
            output.writeInt(info.width)
            output.writeInt(info.height)
            output.writeBoolean(info.canControl)
            output.flush()

            socket.soTimeout = 15_000 // the viewer pings every few seconds
            listener.onClientConnected(socket.inetAddress?.hostAddress ?: "")
            newSession.startSender()
            newSession.readLoop()
        } catch (e: Exception) {
            // connection ended or broke; cleanup below
        } finally {
            val s = mine
            if (s == null) {
                closeQuietly(socket)
            } else {
                s.close()
                var wasCurrent = false
                synchronized(sessionLock) {
                    if (session === s) {
                        session = null
                        wasCurrent = true
                    }
                }
                if (wasCurrent) listener.onClientDisconnected()
            }
        }
    }

    private fun reply(output: DataOutputStream, code: Int) {
        try {
            output.writeByte(code)
            output.flush()
        } catch (e: IOException) {
            // viewer already gone
        }
    }

    private inner class Session(
        private val socket: Socket,
        private val input: DataInputStream,
        private val output: DataOutputStream
    ) {
        private val lock = ReentrantLock()
        private val hasWork = lock.newCondition()
        private var pendingFrame: ByteArray? = null
        private var pendingInfo: ScreenInfo? = null
        @Volatile private var closed = false

        // Only the newest frame is kept, so a slow network drops frames instead of lagging behind.
        fun offerFrame(jpeg: ByteArray) = lock.withLock {
            pendingFrame = jpeg
            hasWork.signalAll()
        }

        fun offerInfo(info: ScreenInfo) = lock.withLock {
            pendingInfo = info
            hasWork.signalAll()
        }

        fun startSender() {
            thread(name = "droidlink-sender", isDaemon = true) { sendLoop() }
        }

        private fun sendLoop() {
            try {
                while (!closed) {
                    val (frame, info) = lock.withLock {
                        if (pendingFrame == null && pendingInfo == null && !closed) {
                            hasWork.await(HEARTBEAT_MS, TimeUnit.MILLISECONDS)
                        }
                        val f = pendingFrame
                        val i = pendingInfo
                        pendingFrame = null
                        pendingInfo = null
                        Pair(f, i)
                    }
                    if (closed) break
                    if (info != null) {
                        output.writeByte(Protocol.MSG_INFO)
                        output.writeInt(info.width)
                        output.writeInt(info.height)
                        output.writeBoolean(info.canControl)
                    }
                    if (frame != null) {
                        output.writeByte(Protocol.MSG_FRAME)
                        output.writeInt(frame.size)
                        output.write(frame)
                    }
                    if (info == null && frame == null) {
                        output.writeByte(Protocol.MSG_HEARTBEAT)
                    }
                    output.flush()
                }
            } catch (e: Exception) {
                close()
            }
        }

        fun readLoop() {
            while (!closed) {
                when (input.readUnsignedByte()) {
                    Protocol.MSG_GESTURE -> {
                        val duration = input.readInt()
                        val count = input.readUnsignedShort()
                        if (count < 1 || count > Protocol.MAX_GESTURE_POINTS) throw IOException("Bad gesture")
                        val points = FloatArray(count * 2)
                        for (i in points.indices) {
                            val v = input.readFloat()
                            if (v.isNaN() || v.isInfinite()) throw IOException("Bad point")
                            points[i] = v
                        }
                        listener.onGesture(points, duration.toLong().coerceIn(1L, 60_000L))
                    }
                    Protocol.MSG_GLOBAL -> listener.onGlobalAction(input.readUnsignedByte())
                    Protocol.MSG_TEXT -> {
                        val mode = input.readUnsignedByte()
                        val text = input.readUTF()
                        listener.onText(mode, text)
                    }
                    Protocol.MSG_PING -> Unit
                    else -> throw IOException("Unknown message")
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            lock.withLock { hasWork.signalAll() }
            closeQuietly(socket)
        }
    }
}
