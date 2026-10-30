package app.droidlink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address

private const val TAG = "DroidLinkNsd"

/** Announces a sharing phone on the local network so the other phone can find it without typing an address. */
class NsdAdvertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register(name: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = Protocol.SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: Exception) {
            Log.w(TAG, "Could not advertise", e)
        }
    }

    fun unregister() {
        val l = listener ?: return
        listener = null
        try {
            nsd.unregisterService(l)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop advertising", e)
        }
    }
}

/** Finds sharing phones on the local network. Callbacks arrive on the main thread. */
class NsdBrowser(context: Context, private val onChanged: (List<Host>) -> Unit) {

    data class Host(val name: String, val host: String, val port: Int)

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Host>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discovery: NsdManager.DiscoveryListener? = null

    fun start() {
        if (discovery != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post { enqueue(serviceInfo) }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (found.remove(serviceInfo.serviceName) != null) publish()
                }
            }
        }
        try {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            discovery = l
        } catch (e: Exception) {
            Log.w(TAG, "Could not start discovery", e)
        }
        publish()
    }

    fun stop() {
        val l = discovery ?: return
        discovery = null
        try {
            nsd.stopServiceDiscovery(l)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop discovery", e)
        }
        queue.clear()
        found.clear()
    }

    private fun enqueue(info: NsdServiceInfo) {
        if (discovery == null) return
        queue.addLast(info)
        resolveNext()
    }

    // The classic resolve API only handles one request at a time, so requests are queued.
    private fun resolveNext() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        resolve(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val address = hostOf(serviceInfo)
                main.post {
                    resolving = false
                    if (address != null && discovery != null) {
                        found[serviceInfo.serviceName] = Host(serviceInfo.serviceName, address, serviceInfo.port)
                        publish()
                    }
                    resolveNext()
                }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
        try {
            nsd.resolveService(info, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Resolve failed", e)
            main.post {
                resolving = false
                resolveNext()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            val addresses = info.hostAddresses
            val preferred = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
            if (preferred != null) return preferred.hostAddress
        }
        return info.host?.hostAddress
    }

    private fun publish() {
        onChanged(found.values.toList())
    }
}
