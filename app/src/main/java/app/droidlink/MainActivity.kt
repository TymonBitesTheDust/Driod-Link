package app.droidlink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View

class MainActivity : Activity() {

    private lateinit var sharingBadge: View
    private val stateListener = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enableEdgeToEdge()
        findViewById<View>(R.id.root).applyInsetPadding()

        sharingBadge = findViewById(R.id.sharing_badge)
        findViewById<View>(R.id.card_share).setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }
        findViewById<View>(R.id.card_control).setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java))
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

    private fun refresh() {
        sharingBadge.visibility = if (ShareState.isSharing) View.VISIBLE else View.GONE
    }
}
