package dev.khanlabs.lanshare.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import dev.khanlabs.lanshare.link.Discovery
import dev.khanlabs.lanshare.protocol.WifiCode
import java.net.Inet4Address

/** What the screens show about the Wi-Fi network this phone made or joined because there was no router. */
data class OwnWifiView(
    val mode: Mode = Mode.Off,
    /** The network's name and password: made by this phone, or the one it joined. */
    val ssid: String = "",
    val psk: String = "",
    /** This phone's addresses on that network. Empty until Android has given it one. */
    val addresses: List<String> = emptyList(),
    /** Why the last try failed, or null. */
    val error: String? = null,
) {
    enum class Mode { Off, Starting, Hosting, Joining, Joined }

    val active: Boolean get() = mode != Mode.Off
    val hosting: Boolean get() = mode == Mode.Hosting
}

/**
 * A Wi-Fi network with no router: this phone makes one with `LocalOnlyHotspot` (Android 8 and newer), or joins one
 * with `WifiNetworkSpecifier` (Android 10 and newer). Both are Android's own, with its own prompts; the network has no
 * internet and only this app's traffic is moved onto a joined one.
 *
 * Everything runs on the main thread. Written from the Android documentation and not yet tried on a phone: the plan is
 * to run it on the OnePlus 8T (Android 14) and at least two other brands before relying on it (docs/PLAN.md section 5.9).
 */
@SuppressLint("NewApi") // every call that needs a newer Android is behind canHost or canJoin
internal class OwnWifi(context: Context, private val main: Handler, private val changed: () -> Unit) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    var view = OwnWifiView()
        private set
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var before: Set<String> = emptySet()
    private var joinCallback: ConnectivityManager.NetworkCallback? = null
    private var joined: Network? = null

    val canHost: Boolean get() = Build.VERSION.SDK_INT >= 26
    val canJoin: Boolean get() = Build.VERSION.SDK_INT >= 29

    private fun set(v: OwnWifiView) {
        view = v
        changed()
    }

    private fun fail(message: String) = set(OwnWifiView(error = message))

    // --- making a network

    /** Starts a hotspot with no internet behind it. Needs the Nearby devices permission (Android 13 and newer) or Location (older). */
    @SuppressLint("MissingPermission")
    fun startHosting() {
        if (!canHost) return fail("This Android version can't make a network from an app. It needs Android 8 or newer.")
        if (view.active) return
        before = Discovery.lanAddresses().toSet()
        set(OwnWifiView(mode = OwnWifiView.Mode.Starting))
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) = hostStarted(r)

                override fun onStopped() {
                    if (view.mode == OwnWifiView.Mode.Hosting) {
                        reservation = null
                        fail("Android turned the network off.")
                    }
                }

                override fun onFailed(reason: Int) = fail(hostError(reason))
            }, main)
        } catch (e: SecurityException) {
            fail("Allow Nearby devices (or Location, on older Android) so the phone can make a network.")
        } catch (e: Exception) {
            fail("Couldn't make a network: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun hostStarted(r: WifiManager.LocalOnlyHotspotReservation) {
        reservation = r
        val (ssid, psk) = try {
            if (Build.VERSION.SDK_INT >= 30) {
                val c = r.softApConfiguration
                (c.ssid ?: "") to (c.passphrase ?: "")
            } else {
                val c = r.wifiConfiguration
                unquote(c?.SSID) to unquote(c?.preSharedKey)
            }
        } catch (e: Exception) {
            "" to ""
        }
        if (!WifiCode.isValid(ssid, psk)) {
            r.close()
            reservation = null
            return fail("The phone made a network Amanah can't describe (no usable name or password), so it was turned off.")
        }
        set(OwnWifiView(mode = OwnWifiView.Mode.Hosting, ssid = ssid, psk = psk))
        findHostAddress(0)
    }

    /** The phone's address on its own network is the one that appears after the hotspot starts. It can take a moment. */
    private fun findHostAddress(attempt: Int) {
        if (view.mode != OwnWifiView.Mode.Hosting) return
        val now = Discovery.lanAddresses().filter { it !in before }
        if (now.isNotEmpty()) {
            set(view.copy(addresses = now))
        } else if (attempt < 20) {
            main.postDelayed({ findHostAddress(attempt + 1) }, 400)
        }
    }

    fun stopHosting() {
        reservation?.close()
        reservation = null
        if (view.mode == OwnWifiView.Mode.Hosting || view.mode == OwnWifiView.Mode.Starting) set(OwnWifiView())
    }

    private fun hostError(reason: Int) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "The phone has no free Wi-Fi channel for a network right now."
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "The phone's Wi-Fi is in a mode that can't make a network. Turn off your hotspot or Wi-Fi Direct and try again."
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "This phone doesn't allow making a network (a work or school policy)."
        else -> "Couldn't make a network. On Android 12 and older, Location must be turned on."
    }

    private fun unquote(s: String?): String = (s ?: "").removeSurrounding("\"")

    // --- joining a network

    /** Joins [ssid] for this app only. Android asks the person first. [result] gets null once the phone has an address, else a message. */
    fun join(ssid: String, psk: String, result: (String?) -> Unit) {
        if (!canJoin) {
            return result("This Android version can't join a network from an app. Open Wi-Fi settings, join \"$ssid\" with the password from the other device, then try again.")
        }
        if (!WifiCode.isValid(ssid, psk)) return result("That network name or password is not valid.")
        leave()
        set(OwnWifiView(mode = OwnWifiView.Mode.Joining, ssid = ssid))
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(psk).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // the network has no internet behind it
            .setNetworkSpecifier(spec)
            .build()
        var answered = false
        fun answer(message: String?) {
            if (answered) return
            answered = true
            result(message)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                joined = network
                cm.bindProcessToNetwork(network) // or this app's traffic would go out over mobile data
                set(view.copy(mode = OwnWifiView.Mode.Joined, ssid = ssid, psk = psk))
                main.postDelayed({ if (joined == network && view.addresses.isEmpty()) {
                    leave()
                    answer("Joined \"$ssid\", but the phone got no address from it.")
                } }, ADDRESS_WAIT_MS)
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (network != joined) return
                val found = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                if (found.isNotEmpty()) {
                    set(view.copy(addresses = found))
                    answer(null)
                }
            }

            override fun onUnavailable() {
                leave()
                answer("Couldn't join \"$ssid\". It wasn't found, the password was wrong, or the request was declined.")
            }

            override fun onLost(network: Network) {
                if (network != joined) return
                cm.bindProcessToNetwork(null)
                joined = null
                joinCallback = null
                fail("The connection to \"$ssid\" was lost.")
            }
        }
        joinCallback = callback
        try {
            cm.requestNetwork(request, callback, main, JOIN_TIMEOUT_MS)
        } catch (e: Exception) {
            joinCallback = null
            fail("Couldn't join: ${e.message}")
            answer("Couldn't join \"$ssid\": ${e.message}")
        }
    }

    /** Lets go of a joined network and puts this app's traffic back on the normal one. */
    fun leave() {
        joinCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // already gone
            }
        }
        joinCallback = null
        if (joined != null) {
            cm.bindProcessToNetwork(null)
            joined = null
        }
        if (view.mode == OwnWifiView.Mode.Joined || view.mode == OwnWifiView.Mode.Joining) set(OwnWifiView())
    }

    fun stopAll() {
        stopHosting()
        leave()
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 30_000
        const val ADDRESS_WAIT_MS = 15_000L
    }
}
