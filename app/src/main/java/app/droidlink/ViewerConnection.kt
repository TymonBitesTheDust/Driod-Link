package app.droidlink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * Connection from the controlling phone to a sharing phone. Listener callbacks arrive on a
 * background thread; sending never blocks the caller.
 */
class ViewerConnection(
    context: Context,
    private val host: String,
    private val port: Int,
    private val pin: String,
    private val listener: Listener
) {
    data class RemoteInfo(val width: Int, val height: Int, val canControl: Boolean)

    interface Listener {
        fun onConnected(info: RemoteInfo)
        fun onFrame(bitmap: Bitmap)
        fun onInfo(info: RemoteInfo)
        fun onDisconnected(reason: String)
    }

    private class Refused(message: String) : Exception(message)

    private val appContext = context.applicationContext
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var closed = false

    fun connect() {
        thread(name = "droidlink-viewer", isDaemon = true) { run() }
    }

    fun close() {
        closed = true
        closeQuietly(socket)
        sender.shutdownNow()
    }

    fun sendGesture(points: FloatArray, durationMs: Int) = send { out ->
        out.writeByte(Protocol.MSG_GESTURE)
        out.writeInt(durationMs)
        out.writeShort(points.size / 2)
        for (v in points) out.writeFloat(v)
    }

    fun sendGlobal(action: Int) = send { out ->
        out.writeByte(Protocol.MSG_GLOBAL)
        out.writeByte(action)
    }

    fun sendText(mode: Int, text: String) = send { out ->
        out.writeByte(Protocol.MSG_TEXT)
        out.writeByte(mode)
        out.writeUTF(text)
    }

    private fun send(block: (DataOutputStream) -> Unit) {
        if (closed) return
        try {
            sender.execute {
                val out = output ?: return@execute
                try {
                    block(out)
                    out.flush()
                } catch (e: IOException) {
                    // the reader thread notices the broken connection
                }
            }
        } catch (e: RejectedExecutionException) {
            // closed
        }
    }

    private fun run() {
        var reason: String? = null
        try {
            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(host, port), 6000)
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 10_000 // the host sends a heartbeat every 2 seconds

            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 16 * 1024))
            out.writeInt(Protocol.MAGIC)
            out.writeInt(Protocol.VERSION)
            out.writeUTF(pin)
            out.flush()

            when (input.readUnsignedByte()) {
                Protocol.AUTH_OK -> Unit
                Protocol.AUTH_BAD_PIN -> throw Refused(appContext.getString(R.string.err_wrong_pin))
                Protocol.AUTH_BUSY -> throw Refused(appContext.getString(R.string.err_busy))
                Protocol.AUTH_BAD_VERSION -> throw Refused(appContext.getString(R.string.err_version))
                else -> throw Refused(appContext.getString(R.string.err_generic))
            }
            val info = RemoteInfo(input.readInt(), input.readInt(), input.readBoolean())
            output = out
            listener.onConnected(info)
            startPinging()

            while (!closed) {
                when (input.readUnsignedByte()) {
                    Protocol.MSG_FRAME -> {
                        val length = input.readInt()
                        if (length <= 0 || length > Protocol.MAX_FRAME_BYTES) throw IOException("Bad frame")
                        val bytes = ByteArray(length)
                        input.readFully(bytes)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, length)
                        if (bitmap != null) listener.onFrame(bitmap)
                    }
                    Protocol.MSG_INFO -> listener.onInfo(RemoteInfo(input.readInt(), input.readInt(), input.readBoolean()))
                    Protocol.MSG_HEARTBEAT -> Unit
                    else -> throw IOException("Unknown message")
                }
            }
        } catch (e: Refused) {
            reason = e.message
        } catch (e: SocketTimeoutException) {
            reason = appContext.getString(R.string.err_timeout)
        } catch (e: ConnectException) {
            reason = appContext.getString(R.string.err_unreachable, "$host:$port")
        } catch (e: NoRouteToHostException) {
            reason = appContext.getString(R.string.err_unreachable, "$host:$port")
        } catch (e: UnknownHostException) {
            reason = appContext.getString(R.string.err_unreachable, host)
        } catch (e: EOFException) {
            reason = appContext.getString(R.string.err_ended)
        } catch (e: Exception) {
            reason = if (output == null) appContext.getString(R.string.err_unreachable, "$host:$port")
            else appContext.getString(R.string.err_ended)
        } finally {
            val wasClosedByUs = closed
            close()
            if (!wasClosedByUs) listener.onDisconnected(reason ?: appContext.getString(R.string.err_generic))
        }
    }

    private fun startPinging() {
        thread(name = "droidlink-ping", isDaemon = true) {
            try {
                while (!closed) {
                    Thread.sleep(3000)
                    send { it.writeByte(Protocol.MSG_PING) }
                }
            } catch (e: InterruptedException) {
                // stopped
            }
        }
    }
}
