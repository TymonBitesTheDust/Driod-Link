package app.droidlink

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicReference

/** Full-screen view of the other phone, with Back / Home / Recents / keyboard buttons. */
class ControlActivity : Activity(), ViewerConnection.Listener, RemoteScreenView.GestureSink {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PIN = "pin"
    }

    private lateinit var screen: RemoteScreenView
    private lateinit var statusOverlay: View
    private lateinit var statusProgress: View
    private lateinit var statusText: TextView
    private lateinit var retryButton: Button
    private lateinit var leaveButton: Button
    private lateinit var viewOnlyBanner: View
    private lateinit var typePanel: View
    private lateinit var typeInput: EditText
    private lateinit var controlButtons: List<View>

    private var connection: ViewerConnection? = null
    private val pendingFrame = AtomicReference<Bitmap?>(null)
    private var host = ""
    private var port = Protocol.DEFAULT_PORT
    private var pin = ""
    private var connected = false
    private var lastBlockedToast = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent.getStringExtra(EXTRA_HOST) ?: run { finish(); return }
        port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT)
        pin = intent.getStringExtra(EXTRA_PIN) ?: ""

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_control)
        findViewById<View>(R.id.root).applyInsetPadding(systemBars = false)

        screen = findViewById(R.id.screen)
        statusOverlay = findViewById(R.id.status_overlay)
        statusProgress = findViewById(R.id.status_progress)
        statusText = findViewById(R.id.status_text)
        retryButton = findViewById(R.id.btn_retry)
        leaveButton = findViewById(R.id.btn_leave)
        viewOnlyBanner = findViewById(R.id.view_only_banner)
        typePanel = findViewById(R.id.type_panel)
        typeInput = findViewById(R.id.type_input)
        screen.sink = this

        val back = findViewById<View>(R.id.btn_nav_back)
        val home = findViewById<View>(R.id.btn_nav_home)
        val recents = findViewById<View>(R.id.btn_nav_recents)
        val keyboard = findViewById<View>(R.id.btn_keyboard)
        controlButtons = listOf(back, home, recents, keyboard)

        back.setOnClickListener { connection?.sendGlobal(Protocol.GLOBAL_BACK) }
        home.setOnClickListener { connection?.sendGlobal(Protocol.GLOBAL_HOME) }
        home.setOnLongClickListener {
            connection?.sendGlobal(Protocol.GLOBAL_NOTIFICATIONS)
            true
        }
        recents.setOnClickListener { connection?.sendGlobal(Protocol.GLOBAL_RECENTS) }
        keyboard.setOnClickListener { toggleTypePanel() }
        findViewById<View>(R.id.btn_disconnect).setOnClickListener { finish() }

        findViewById<View>(R.id.btn_send).setOnClickListener { sendTyped() }
        findViewById<View>(R.id.btn_backspace).setOnClickListener {
            connection?.sendText(Protocol.TEXT_BACKSPACE, "")
        }
        findViewById<View>(R.id.btn_enter).setOnClickListener {
            sendTyped()
            connection?.sendText(Protocol.TEXT_ENTER, "")
        }
        typeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else {
                false
            }
        }

        retryButton.setOnClickListener { connect() }
        leaveButton.setOnClickListener { finish() }

        setCanControl(false)
        connect()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        connection?.close()
        connection = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            typePanel.visibility == View.VISIBLE -> hideTypePanel()
            !connected -> finish()
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.disconnect_title)
                .setMessage(R.string.disconnect_message)
                .setPositiveButton(R.string.disconnect) { _, _ -> finish() }
                .setNegativeButton(R.string.stay, null)
                .show()
        }
    }

    private fun connect() {
        connection?.close()
        connected = false
        showStatus(getString(R.string.connecting_to, "$host:$port"), busy = true)
        connection = ViewerConnection(this, host, port, pin, this).also { it.connect() }
    }

    private fun showStatus(message: String, busy: Boolean) {
        statusOverlay.visibility = View.VISIBLE
        statusText.text = message
        statusProgress.visibility = if (busy) View.VISIBLE else View.GONE
        retryButton.visibility = if (busy) View.GONE else View.VISIBLE
        leaveButton.visibility = if (busy) View.GONE else View.VISIBLE
    }

    private fun setCanControl(canControl: Boolean) {
        screen.controlEnabled = canControl
        viewOnlyBanner.visibility = if (canControl || !connected) View.GONE else View.VISIBLE
        for (b in controlButtons) {
            b.isEnabled = canControl
            b.alpha = if (canControl) 1f else 0.35f
        }
        if (!canControl) hideTypePanel()
    }

    // ---- Typing ----

    private fun toggleTypePanel() {
        if (typePanel.visibility == View.VISIBLE) {
            hideTypePanel()
        } else {
            typePanel.visibility = View.VISIBLE
            typeInput.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(typeInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideTypePanel() {
        if (typePanel.visibility != View.VISIBLE) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(typeInput.windowToken, 0)
        typePanel.visibility = View.GONE
    }

    private fun sendTyped() {
        val text = typeInput.text.toString()
        if (text.isNotEmpty()) {
            connection?.sendText(Protocol.TEXT_APPEND, text)
            typeInput.text.clear()
        }
    }

    // ---- Full screen ----

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            legacyHideSystemBars()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyHideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    // ---- RemoteScreenView.GestureSink ----

    override fun onRemoteGesture(points: FloatArray, durationMs: Int) {
        connection?.sendGesture(points, durationMs)
    }

    override fun onBlockedGesture() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBlockedToast > 4000) {
            lastBlockedToast = now
            Toast.makeText(this, R.string.view_only_toast, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- ViewerConnection.Listener (background thread) ----

    override fun onConnected(info: ViewerConnection.RemoteInfo) = runOnUiThread {
        connected = true
        statusOverlay.visibility = View.GONE
        setCanControl(info.canControl)
    }

    override fun onFrame(bitmap: Bitmap) {
        // Coalesce: if the UI hasn't drawn the previous frame yet, just replace it.
        if (pendingFrame.getAndSet(bitmap) == null) {
            screen.post { pendingFrame.getAndSet(null)?.let { screen.setFrame(it) } }
        }
    }

    override fun onInfo(info: ViewerConnection.RemoteInfo) = runOnUiThread {
        setCanControl(info.canControl)
    }

    override fun onDisconnected(reason: String) = runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        connected = false
        setCanControl(false)
        showStatus(reason, busy = false)
    }
}
