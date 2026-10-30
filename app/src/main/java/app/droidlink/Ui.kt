package app.droidlink

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import java.io.Closeable
import java.io.IOException

/** Draw behind the system bars on every Android version, so insets are handled the same way everywhere. */
fun Activity.enableEdgeToEdge() {
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
    } else {
        legacyEdgeToEdge(window)
    }
}

@Suppress("DEPRECATION")
private fun legacyEdgeToEdge(window: Window) {
    val decor = window.decorView
    decor.systemUiVisibility = decor.systemUiVisibility or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
}

/**
 * Pads this view by the system bar, display cutout and keyboard insets, on top of its own padding.
 * Pass [systemBars] = false for full-screen layouts that hide the bars.
 */
fun View.applyInsetPadding(systemBars: Boolean = true) {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        val values = insetValues(insets, systemBars)
        v.setPadding(left + values[0], top + values[1], right + values[2], bottom + values[3])
        insets
    }
    requestApplyInsets()
}

private fun insetValues(insets: WindowInsets, systemBars: Boolean): IntArray {
    if (Build.VERSION.SDK_INT >= 30) {
        var types = WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
        if (systemBars) types = types or WindowInsets.Type.systemBars()
        val i = insets.getInsets(types)
        return intArrayOf(i.left, i.top, i.right, i.bottom)
    }
    return legacyInsetValues(insets)
}

@Suppress("DEPRECATION")
private fun legacyInsetValues(insets: WindowInsets): IntArray = intArrayOf(
    insets.systemWindowInsetLeft,
    insets.systemWindowInsetTop,
    insets.systemWindowInsetRight,
    insets.systemWindowInsetBottom
)

fun closeQuietly(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (e: IOException) {
        // nothing useful to do
    }
}
