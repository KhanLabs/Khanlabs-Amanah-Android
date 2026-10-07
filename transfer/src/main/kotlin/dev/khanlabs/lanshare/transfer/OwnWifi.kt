package dev.khanlabs.lanshare.transfer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import dev.khanlabs.lanshare.link.Discovery
import dev.khanlabs.lanshare.protocol.WifiCode
import java.net.Inet4Address
import java.security.SecureRandom

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
    /** The band the person asked for when the network was made. */
    val band: OwnBand = OwnBand.Auto,
    /** The radio frequency Android really gave the network, in MHz, or 0 when it does not say. 2412 to 2484 is 2.4 GHz. */
    val mhz: Int = 0,
) {
    enum class Mode { Off, Starting, Hosting, Joining, Joined }

    val active: Boolean get() = mode != Mode.Off
    val hosting: Boolean get() = mode == Mode.Hosting

    /** True when the network runs on 2.4 GHz, false on 5 GHz or 6 GHz, null when Android does not say. */
    val slowBand: Boolean? get() = if (mhz <= 0) null else mhz < 3000
}

/** Which Wi-Fi band the phone's own network uses. [Auto] lets Android choose; the other two need Wi-Fi Direct (Android 10 and newer). */
enum class OwnBand { Auto, Ghz24, Ghz5 }

/** What this phone can do about the band: [canPick] is false when Android or the hardware gives no choice. */
data class BandSupport(val canPick: Boolean, val has5Ghz: Boolean) {
    /** There is a real choice to show only when the phone can both pick and use 5 GHz. */
    val offer: Boolean get() = canPick && has5Ghz
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

    // a Wi-Fi Direct group: how a band is chosen, because LocalOnlyHotspot lets Android pick
    private var p2p: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pWatcher: BroadcastReceiver? = null

    val canHost: Boolean get() = Build.VERSION.SDK_INT >= 26
    val canJoin: Boolean get() = Build.VERSION.SDK_INT >= 29

    val bandSupport: BandSupport
        get() = BandSupport(
            canPick = Build.VERSION.SDK_INT >= 29 && app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
            has5Ghz = try { wifi.is5GHzBandSupported() } catch (e: Exception) { false },
        )

    private fun set(v: OwnWifiView) {
        view = v
        changed()
    }

    private fun fail(message: String) = set(OwnWifiView(error = message))

    // --- making a network

    /**
     * Starts a network with no internet behind it. With [band] Auto it is a LocalOnlyHotspot and Android picks the band;
     * with 2.4 or 5 GHz it is a Wi-Fi Direct group, which other devices join like any Wi-Fi network.
     * Needs the Nearby devices permission (Android 13 and newer) or Location (older).
     */
    @SuppressLint("MissingPermission")
    fun startHosting(band: OwnBand = OwnBand.Auto) {
        if (!canHost) return fail("This Android version can't make a network from an app. It needs Android 8 or newer.")
        if (view.active) return
        if (band != OwnBand.Auto) {
            val support = bandSupport
            if (!support.canPick) return fail("This phone can't pick a band for its network. Use Automatic.")
            if (band == OwnBand.Ghz5 && !support.has5Ghz) return fail("This phone can't use 5 GHz. Use Automatic or 2.4 GHz.")
        }
        before = Discovery.lanAddresses().toSet()
        set(OwnWifiView(mode = OwnWifiView.Mode.Starting, band = band))
        if (band != OwnBand.Auto) return startGroup(band)
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
        leaveGroup()
        if (view.mode == OwnWifiView.Mode.Hosting || view.mode == OwnWifiView.Mode.Starting) set(OwnWifiView())
    }

    // --- a network on a band the person chose: a Wi-Fi Direct group

    @SuppressLint("MissingPermission")
    private fun startGroup(band: OwnBand) {
        val mgr = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        val channel = mgr?.initialize(app, main.looper) { failGroup("Wi-Fi Direct stopped working.") }
        if (mgr == null || channel == null) return fail("This phone has no Wi-Fi Direct, so it can't pick a band. Use Automatic.")
        p2p = mgr
        p2pChannel = channel
        val password = randomText(12, PASSWORD_CHARS)
        val config = WifiP2pConfig.Builder()
            .setNetworkName("DIRECT-" + randomText(2, NAME_CHARS) + DIRECT_SUFFIX)
            .setPassphrase(password)
            .enablePersistentMode(false)
            .setGroupOperatingBand(
                if (band == OwnBand.Ghz5) WifiP2pConfig.GROUP_OWNER_BAND_5GHZ else WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            .build()
        fun create() {
            if (p2pChannel !== channel) return // turned off while waiting
            mgr.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = watchGroup(mgr, channel, band, password, 0)
                override fun onFailure(reason: Int) = groupFailed(band, reason)
            })
        }
        try {
            mgr.requestGroupInfo(channel) { old ->
                when {
                    old == null -> create()
                    // one this app left behind (it was killed while the network was on): clear it. Anyone else's is left alone.
                    old.networkName?.endsWith(DIRECT_SUFFIX) == true -> mgr.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { main.postDelayed({ create() }, 600) }
                        override fun onFailure(reason: Int) = create()
                    })
                    else -> failGroup("Wi-Fi Direct is already connected to another device. Disconnect it in the phone's Wi-Fi settings, or use Automatic.")
                }
            }
        } catch (e: SecurityException) {
            failGroup("Allow Nearby devices (or Location, on older Android) so the phone can make a network.")
        }
    }

    /** The group forms a moment after createGroup says yes, so ask until it is there. */
    @SuppressLint("MissingPermission")
    private fun watchGroup(mgr: WifiP2pManager, channel: WifiP2pManager.Channel, band: OwnBand, password: String, attempt: Int) {
        if (p2pChannel !== channel || view.mode != OwnWifiView.Mode.Starting) return
        try {
            mgr.requestGroupInfo(channel) { g ->
                if (p2pChannel !== channel || view.mode != OwnWifiView.Mode.Starting) return@requestGroupInfo
                when {
                    g != null && g.isGroupOwner && !g.networkName.isNullOrEmpty() -> groupStarted(g, band, password)
                    attempt < 25 -> main.postDelayed({ watchGroup(mgr, channel, band, password, attempt + 1) }, 400)
                    else -> failGroup("The network did not start. Try again, or use Automatic.")
                }
            }
        } catch (e: SecurityException) {
            failGroup("Allow Nearby devices (or Location, on older Android) so the phone can make a network.")
        }
    }

    private fun groupStarted(g: WifiP2pGroup, band: OwnBand, password: String) {
        val ssid = g.networkName ?: ""
        val psk = g.passphrase?.takeIf { it.isNotEmpty() } ?: password
        if (!WifiCode.isValid(ssid, psk)) {
            return failGroup("The phone made a network Amanah can't describe (no usable name or password), so it was turned off.")
        }
        val mhz = if (Build.VERSION.SDK_INT >= 33) g.frequency.coerceAtLeast(0) else 0 // what Android really gave, not what was asked
        watchGroupLoss()
        set(OwnWifiView(mode = OwnWifiView.Mode.Hosting, ssid = ssid, psk = psk, band = band, mhz = mhz))
        findHostAddress(0)
    }

    /** Android ends the group when Wi-Fi is turned off or another Wi-Fi Direct use takes over; say so instead of showing a dead network. */
    @SuppressLint("MissingPermission")
    private fun watchGroupLoss() {
        val mgr = p2p ?: return
        val channel = p2pChannel ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (view.mode != OwnWifiView.Mode.Hosting || p2pChannel !== channel) return
                try {
                    mgr.requestGroupInfo(channel) { g ->
                        if (g == null && view.mode == OwnWifiView.Mode.Hosting && p2pChannel === channel) failGroup("Android turned the network off.")
                    }
                } catch (e: SecurityException) {
                    // the answer is not needed enough to stop the network
                }
            }
        }
        p2pWatcher = receiver
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
    }

    private fun groupFailed(band: OwnBand, reason: Int) = failGroup(when {
        reason == WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Turn Wi-Fi off and on, then try again, or use Automatic."
        reason == WifiP2pManager.P2P_UNSUPPORTED -> "This phone has no Wi-Fi Direct, so it can't pick a band. Use Automatic."
        band == OwnBand.Ghz5 ->
            "The phone couldn't make a 5 GHz network. It may not allow 5 GHz while it is also on Wi-Fi: turn Wi-Fi off, or use Automatic."
        else -> "The phone couldn't make a 2.4 GHz network. Try again, or use Automatic."
    })

    private fun failGroup(message: String) {
        leaveGroup()
        fail(message)
    }

    /** Ends the group and gives the Wi-Fi Direct channel back. Safe to call when there is none. */
    private fun leaveGroup() {
        p2pWatcher?.let {
            try {
                app.unregisterReceiver(it)
            } catch (e: Exception) {
                // already gone
            }
        }
        p2pWatcher = null
        val mgr = p2p
        val channel = p2pChannel
        p2p = null
        p2pChannel = null
        if (mgr == null || channel == null) return
        fun close() {
            if (Build.VERSION.SDK_INT >= 27) channel.close()
        }
        try {
            mgr.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = close()
                override fun onFailure(reason: Int) = close()
            })
        } catch (e: Exception) {
            close()
        }
    }

    private fun randomText(n: Int, chars: String): String {
        val random = SecureRandom()
        return String(CharArray(n) { chars[random.nextInt(chars.length)] })
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
        /** A Wi-Fi Direct name is "DIRECT-" and two letters or digits, then anything; this ending marks groups made by Amanah. */
        const val DIRECT_SUFFIX = "-Amanah"
        const val NAME_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val PASSWORD_CHARS = "abcdefghijkmnpqrstuvwxyz23456789" // no letters that look like digits
        const val JOIN_TIMEOUT_MS = 30_000
        const val ADDRESS_WAIT_MS = 15_000L
    }
}
