package app.droidlink

/**
 * Wire protocol between the two phones. Plain TCP, big-endian (DataInput/DataOutput).
 *
 * Viewer -> Host handshake: int MAGIC, int VERSION, UTF pin
 * Host -> Viewer reply:     byte AUTH_*; when AUTH_OK also int width, int height, bool canControl
 *
 * Host -> Viewer messages:
 *   MSG_FRAME     int length, JPEG bytes
 *   MSG_INFO      int width, int height, bool canControl
 *   MSG_HEARTBEAT (no body)
 *
 * Viewer -> Host messages:
 *   MSG_GESTURE   int durationMs, ushort count, count x (float x, float y) normalised 0..1
 *   MSG_GLOBAL    byte GLOBAL_*
 *   MSG_TEXT      byte TEXT_*, UTF text
 *   MSG_PING      (no body)
 */
object Protocol {
    const val MAGIC = 0x444C4E4B // "DLNK"
    const val VERSION = 1
    const val DEFAULT_PORT = 47800
    const val SERVICE_TYPE = "_droidlink._tcp"

    const val AUTH_OK = 1
    const val AUTH_BAD_PIN = 2
    const val AUTH_BUSY = 3
    const val AUTH_BAD_VERSION = 4

    const val MSG_FRAME = 1
    const val MSG_INFO = 2
    const val MSG_HEARTBEAT = 3

    const val MSG_GESTURE = 10
    const val MSG_GLOBAL = 11
    const val MSG_TEXT = 12
    const val MSG_PING = 13

    const val GLOBAL_BACK = 1
    const val GLOBAL_HOME = 2
    const val GLOBAL_RECENTS = 3
    const val GLOBAL_NOTIFICATIONS = 4
    const val GLOBAL_QUICK_SETTINGS = 5
    const val GLOBAL_LOCK = 6

    const val TEXT_APPEND = 0
    const val TEXT_BACKSPACE = 1
    const val TEXT_ENTER = 2

    const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    const val MAX_GESTURE_POINTS = 120
}
