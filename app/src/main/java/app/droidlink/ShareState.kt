package app.droidlink

import android.os.Handler
import android.os.Looper
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** Sharing state, written by [ScreenShareService] and read by the screens. */
object ShareState {
    enum class Status { IDLE, WAITING, CONNECTED }

    @Volatile var status: Status = Status.IDLE
    @Volatile var pin: String = ""
    @Volatile var port: Int = 0
    @Volatile var peer: String? = null
    @Volatile var lastError: String? = null

    private val listeners = CopyOnWriteArraySet<Runnable>()
    private val main = Handler(Looper.getMainLooper())

    val isSharing: Boolean get() = status != Status.IDLE

    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    fun update(block: ShareState.() -> Unit) {
        this.block()
        notifyChanged()
    }

    fun notifyChanged() {
        main.post { listeners.forEach { it.run() } }
    }
}

private val random = SecureRandom()

fun generatePin(): String = String.format(Locale.US, "%06d", random.nextInt(1_000_000))

fun formatPin(pin: String): String =
    if (pin.length == 6) pin.substring(0, 3) + " " + pin.substring(3) else pin
