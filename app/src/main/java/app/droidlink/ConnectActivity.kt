package app.droidlink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Pick or type the sharing phone, enter its PIN, connect. */
class ConnectActivity : Activity() {

    companion object {
        private const val PREFS = "droidlink"
        private const val KEY_LAST_ADDRESS = "last_address"

        /** "192.168.1.5" or "192.168.1.5:47800" -> host and port. */
        fun parseAddress(raw: String): Pair<String, Int>? {
            val s = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            if (s.isEmpty()) return null
            val first = s.indexOf(':')
            val last = s.lastIndexOf(':')
            if (first > 0 && first == last) {
                val host = s.substring(0, last).trim()
                val port = s.substring(last + 1).trim().toIntOrNull() ?: return null
                if (host.isEmpty() || port !in 1..65535) return null
                return host to port
            }
            return s to Protocol.DEFAULT_PORT
        }
    }

    private lateinit var browser: NsdBrowser
    private lateinit var nearbyList: LinearLayout
    private lateinit var nearbyEmpty: View
    private lateinit var addressInput: EditText
    private lateinit var pinInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        enableEdgeToEdge()
        findViewById<View>(R.id.root).applyInsetPadding()

        nearbyList = findViewById(R.id.nearby_list)
        nearbyEmpty = findViewById(R.id.nearby_empty)
        addressInput = findViewById(R.id.address_input)
        pinInput = findViewById(R.id.pin_input)

        addressInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_ADDRESS, ""))
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_connect).setOnClickListener { connect() }
        pinInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                connect()
                true
            } else {
                false
            }
        }
        browser = NsdBrowser(this) { hosts -> showHosts(hosts) }
    }

    override fun onResume() {
        super.onResume()
        browser.start()
    }

    override fun onPause() {
        browser.stop()
        super.onPause()
    }

    private fun showHosts(hosts: List<NsdBrowser.Host>) {
        for (i in nearbyList.childCount - 1 downTo 0) {
            if (nearbyList.getChildAt(i) !== nearbyEmpty) nearbyList.removeViewAt(i)
        }
        nearbyEmpty.visibility = if (hosts.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (host in hosts) {
            val address = "${host.host}:${host.port}"
            val row = inflater.inflate(R.layout.item_nearby, nearbyList, false)
            row.findViewById<TextView>(R.id.nearby_name).text = host.name
            row.findViewById<TextView>(R.id.nearby_address).text = address
            row.setOnClickListener {
                addressInput.setText(address)
                addressInput.error = null
                pinInput.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT)
            }
            nearbyList.addView(row)
        }
    }

    private fun connect() {
        val raw = addressInput.text.toString()
        val pin = pinInput.text.toString().filter { it.isDigit() }
        val target = parseAddress(raw)
        if (target == null) {
            addressInput.error = getString(R.string.error_address)
            addressInput.requestFocus()
            return
        }
        if (pin.length != 6) {
            pinInput.error = getString(R.string.error_pin)
            pinInput.requestFocus()
            return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_ADDRESS, raw.trim()).apply()
        startActivity(
            Intent(this, ControlActivity::class.java)
                .putExtra(ControlActivity.EXTRA_HOST, target.first)
                .putExtra(ControlActivity.EXTRA_PORT, target.second)
                .putExtra(ControlActivity.EXTRA_PIN, pin)
        )
    }
}
