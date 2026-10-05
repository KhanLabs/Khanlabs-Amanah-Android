package dev.khanlabs.lanshare.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.khanlabs.lanshare.net.Device
import dev.khanlabs.lanshare.protocol.DeviceId
import dev.khanlabs.lanshare.protocol.V1
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** What this device announces (PROTOCOL.md section 2). */
data class Announcement(val id: String, val name: String, val port: Int, val app: String, val model: String = "android")

/**
 * DNS-SD with NsdManager: announces this device and finds others offering `_klshare._tcp`.
 * Every callback from here arrives on the main thread.
 */
class Discovery(context: Context, private val ownId: String, private val onChange: (List<Device>) -> Unit) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private var registration: NsdManager.RegistrationListener? = null
    private var announced: Announcement? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null

    /** Service name -> the device it resolved to. */
    private val found = LinkedHashMap<String, Device>()
    /** Resolving one service at a time: older Android fails a second resolve with FAILURE_ALREADY_ACTIVE. */
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    // --- announcing

    fun announce(a: Announcement) {
        if (a == announced) return
        unannounce()
        val info = NsdServiceInfo().apply {
            serviceName = instanceName(a.name, a.id)
            serviceType = SERVICE_TYPE
            port = a.port
            setAttribute("id", a.id)
            setAttribute("name", a.name.take(63))
            setAttribute("model", a.model)
            setAttribute("v", V1.PROTOCOL.toString())
            setAttribute("app", a.app)
            setAttribute("share", "")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                main.post { if (registration === this) { registration = null; announced = null } }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        registration = listener
        announced = a
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            registration = null
            announced = null
        }
    }

    fun unannounce() {
        registration?.let {
            try {
                nsd.unregisterService(it)
            } catch (e: Exception) {
                // already gone
            }
        }
        registration = null
        announced = null
    }

    // --- browsing

    fun startBrowsing() {
        if (discovery != null) return
        lock = wifi.createMulticastLock("klshare-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { if (discovery === this) discovery = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { queue.addLast(info); resolveNext() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    queue.removeAll { it.serviceName == info.serviceName }
                    if (found.remove(info.serviceName) != null) publish()
                }
            }
        }
        discovery = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            discovery = null
        }
    }

    fun stopBrowsing() {
        discovery?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (e: Exception) {
                // already stopped
            }
        }
        discovery = null
        queue.clear()
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        if (found.isNotEmpty()) {
            found.clear()
            publish()
        }
    }

    /** Looks again from scratch, for a "refresh" button. */
    fun refresh() {
        stopBrowsing()
        startBrowsing()
    }

    @Suppress("DEPRECATION") // resolveService is the only way that works on every version from 23
    private fun resolveNext() {
        if (resolving || discovery == null) return
        val next = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post {
                    resolving = false
                    toDevice(info)?.let { found[info.serviceName] = it; publish() }
                    resolveNext()
                }
            }
        })
    }

    private fun publish() = onChange(found.values.distinctBy { it.id })

    @Suppress("DEPRECATION")
    private fun toDevice(info: NsdServiceInfo): Device? {
        val attrs = info.attributes ?: return null
        fun attr(k: String) = attrs[k]?.toString(Charsets.UTF_8) ?: ""
        val id = attr("id")
        if (!DeviceId.isValid(id) || id == ownId) return null
        val hosts = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val ordered = hosts.filterIsInstance<Inet4Address>().map { it.hostAddress!! } +
            hosts.filter { it !is Inet4Address && !it.isLinkLocalAddress }.mapNotNull { it.hostAddress }
        if (ordered.isEmpty()) return null
        return Device(id, attr("name").ifEmpty { info.serviceName }, attr("model"), ordered, info.port, attr("app"))
    }

    companion object {
        const val SERVICE_TYPE = "_klshare._tcp"

        /** Mobile data, VPN and dummy interfaces: other devices on the LAN can't reach these addresses. */
        private val NOT_LAN = listOf("rmnet", "ccmni", "v4-rmnet", "pdp", "tun", "dummy", "ppp")

        /** "<name> <first 6 hex digits of the ID>", like the Windows app. DNS-SD labels are at most 63 bytes. */
        fun instanceName(name: String, id: String): String {
            val suffix = " " + id.take(6)
            var base = name.ifBlank { "Android" }
            while ((base + suffix).toByteArray(Charsets.UTF_8).size > 63) base = base.dropLast(1)
            return base + suffix
        }

        /** This device's IPv4 LAN addresses, best first (Wi-Fi before others). */
        fun lanAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && NOT_LAN.none { p -> it.name.startsWith(p) } }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>() }
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map { (it as InetAddress).hostAddress!! }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
