package app.droidlink

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service on the sharing phone. It is the only part of Android that lets an app
 * tap, swipe and press Back/Home on behalf of the user. It does nothing on its own: it only
 * replays commands from a connected viewer while sharing is on.
 */
class RemoteControlService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteControl"

        @Volatile var instance: RemoteControlService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, RemoteControlService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ShareState.notifyChanged()
        ScreenShareService.current?.onControlAvailabilityChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        if (instance === this) {
            instance = null
            ShareState.notifyChanged()
            ScreenShareService.current?.onControlAvailabilityChanged()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** [points] are screen pixels: x0, y0, x1, y1, ... A single point is a tap or long press. */
    fun performGesture(points: FloatArray, durationMs: Long) {
        if (points.size < 2) return
        main.post {
            try {
                val path = Path()
                path.moveTo(points[0], points[1])
                var i = 2
                while (i + 1 < points.size) {
                    path.lineTo(points[i], points[i + 1])
                    i += 2
                }
                val duration = durationMs.coerceIn(1L, GestureDescription.getMaxGestureDuration())
                val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Gesture failed", e)
            }
        }
    }

    fun performRemoteAction(action: Int) {
        val global = when (action) {
            Protocol.GLOBAL_BACK -> GLOBAL_ACTION_BACK
            Protocol.GLOBAL_HOME -> GLOBAL_ACTION_HOME
            Protocol.GLOBAL_RECENTS -> GLOBAL_ACTION_RECENTS
            Protocol.GLOBAL_NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            Protocol.GLOBAL_QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            Protocol.GLOBAL_LOCK -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return
            else -> return
        }
        main.post { performGlobalAction(global) }
    }

    /** Types into whichever text field has focus on this phone. */
    fun typeText(mode: Int, text: String) {
        main.post {
            try {
                val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@post
                val existing = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
                when (mode) {
                    Protocol.TEXT_APPEND -> if (text.isNotEmpty()) setText(node, existing + text)
                    Protocol.TEXT_BACKSPACE -> if (existing.isNotEmpty()) setText(node, existing.dropLast(1))
                    Protocol.TEXT_ENTER -> pressEnter(node, existing)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Typing failed", e)
            }
        }
    }

    private fun pressEnter(node: AccessibilityNodeInfo, existing: String) {
        if (Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else if (node.isMultiLine) {
            setText(node, existing + "\n")
        }
    }

    private fun setText(node: AccessibilityNodeInfo, value: String) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
