package app.droidlink

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/** The phone that gets viewed and controlled. */
class HostActivity : Activity() {

    companion object {
        private const val REQUEST_CAPTURE = 10
        private const val REQUEST_NOTIFICATIONS = 11
    }

    private lateinit var controlChip: TextView
    private lateinit var enableControlButton: Button
    private lateinit var restrictedHelp: View
    private lateinit var simpleHint: View
    private lateinit var shareChip: TextView
    private lateinit var shareBody: TextView
    private lateinit var connectInfo: View
    private lateinit var addressLabel: TextView
    private lateinit var addressText: TextView
    private lateinit var tailscaleLabel: TextView
    private lateinit var tailscaleText: TextView
    private lateinit var pinText: TextView
    private lateinit var peerText: TextView
    private lateinit var shareButton: Button

    private val stateListener = Runnable { refresh() }
    private var lastShownError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)
        enableEdgeToEdge()
        findViewById<View>(R.id.root).applyInsetPadding()

        controlChip = findViewById(R.id.control_chip)
        enableControlButton = findViewById(R.id.btn_enable_control)
        restrictedHelp = findViewById(R.id.restricted_help)
        simpleHint = findViewById(R.id.control_hint_simple)
        shareChip = findViewById(R.id.share_chip)
        shareBody = findViewById(R.id.share_body)
        connectInfo = findViewById(R.id.connect_info)
        addressLabel = findViewById(R.id.address_label)
        addressText = findViewById(R.id.address_text)
        tailscaleLabel = findViewById(R.id.tailscale_label)
        tailscaleText = findViewById(R.id.tailscale_text)
        pinText = findViewById(R.id.pin_text)
        peerText = findViewById(R.id.peer_text)
        shareButton = findViewById(R.id.btn_share)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        enableControlButton.setOnClickListener { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<View>(R.id.btn_app_info).setOnClickListener {
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        shareButton.setOnClickListener {
            if (ShareState.isSharing) stopSharing() else startSharing()
        }
    }

    override fun onResume() {
        super.onResume()
        ShareState.addListener(stateListener)
        refresh()
    }

    override fun onPause() {
        ShareState.removeListener(stateListener)
        super.onPause()
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun startSharing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The notification is how you stop sharing from anywhere, so ask for it first.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        requestScreenCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Whole screen only: controlling a single app's window would put taps in the wrong place.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val service = Intent(this, ScreenShareService::class.java)
                .setAction(ScreenShareService.ACTION_START)
                .putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
            startForegroundService(service)
        } else {
            Toast.makeText(this, R.string.share_cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSharing() {
        startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP))
    }

    private fun refresh() {
        val controlOn = RemoteControlService.isEnabled(this)
        setChip(controlChip, controlOn, R.string.chip_on)
        enableControlButton.visibility = if (controlOn) View.GONE else View.VISIBLE
        // Android 13+ blocks accessibility services of apps installed from an APK until the
        // user allows restricted settings in App info, so show the step-by-step guide there.
        val hasRestrictedSettings = Build.VERSION.SDK_INT >= 33
        restrictedHelp.visibility = if (!controlOn && hasRestrictedSettings) View.VISIBLE else View.GONE
        simpleHint.visibility = if (!controlOn && !hasRestrictedSettings) View.VISIBLE else View.GONE

        val sharing = ShareState.isSharing
        setChip(shareChip, sharing, R.string.chip_live)
        connectInfo.visibility = if (sharing) View.VISIBLE else View.GONE
        shareBody.setText(if (sharing) R.string.host_share_body else R.string.host_share_body_idle)
        if (sharing) {
            val addresses = NetUtils.localAddresses()
            val tailscale = NetUtils.tailscaleAddress()
            // On Wi-Fi only: "Address". With Tailscale on too, say which address is for what.
            // Tailscale over mobile data with no Wi-Fi: only the Tailscale address.
            val showLocal = addresses.isNotEmpty() || tailscale == null
            addressLabel.visibility = if (showLocal) View.VISIBLE else View.GONE
            addressText.visibility = if (showLocal) View.VISIBLE else View.GONE
            addressLabel.setText(if (tailscale != null) R.string.label_address_wifi else R.string.label_address)
            addressText.text = if (addresses.isEmpty()) {
                getString(R.string.no_network)
            } else {
                addresses.joinToString("\n") { "$it:${ShareState.port}" }
            }
            addressText.textSize = if (addresses.isEmpty()) 15f else 22f
            tailscaleLabel.visibility = if (tailscale != null) View.VISIBLE else View.GONE
            tailscaleText.visibility = if (tailscale != null) View.VISIBLE else View.GONE
            // The first row on screen doesn't need the gap above it
            val gap = if (showLocal) (16 * resources.displayMetrics.density).toInt() else 0
            val params = tailscaleLabel.layoutParams as ViewGroup.MarginLayoutParams
            if (params.topMargin != gap) {
                params.topMargin = gap
                tailscaleLabel.layoutParams = params
            }
            tailscaleText.text = tailscale?.let { "$it:${ShareState.port}" }
            pinText.text = formatPin(ShareState.pin)
            peerText.text = if (ShareState.status == ShareState.Status.CONNECTED) {
                getString(R.string.host_connected, ShareState.peer ?: "")
            } else {
                getString(R.string.host_waiting)
            }
            peerText.setTextColor(getColor(if (ShareState.status == ShareState.Status.CONNECTED) R.color.success else R.color.text_secondary))
        }

        shareButton.setText(if (sharing) R.string.stop_sharing else R.string.start_sharing)
        shareButton.setBackgroundResource(if (sharing) R.drawable.bg_btn_danger else R.drawable.bg_btn_primary)
        shareButton.setTextColor(getColor(if (sharing) R.color.danger else R.color.on_accent))

        val error = ShareState.lastError
        if (error != null && error != lastShownError) {
            lastShownError = error
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
    }

    private fun setChip(chip: TextView, on: Boolean, onText: Int) {
        chip.setText(if (on) onText else R.string.chip_off)
        chip.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        chip.setTextColor(getColor(if (on) R.color.success else R.color.chip_off_text))
    }
}
