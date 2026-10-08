package dev.khanlabs.lanshare.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import dev.khanlabs.lanshare.link.Announcement
import dev.khanlabs.lanshare.link.Discovery
import dev.khanlabs.lanshare.net.AutoOff
import dev.khanlabs.lanshare.net.ChatMessage
import dev.khanlabs.lanshare.net.ChatStore
import dev.khanlabs.lanshare.net.Clients
import dev.khanlabs.lanshare.net.Device
import dev.khanlabs.lanshare.net.ErrorLog
import dev.khanlabs.lanshare.net.ErrorReport
import dev.khanlabs.lanshare.net.Errors
import dev.khanlabs.lanshare.net.Identity
import dev.khanlabs.lanshare.net.IncomingFile
import dev.khanlabs.lanshare.net.Me
import dev.khanlabs.lanshare.net.Offer
import dev.khanlabs.lanshare.net.Receiver
import dev.khanlabs.lanshare.net.ReceiverEvents
import dev.khanlabs.lanshare.net.ReceiverSettings
import dev.khanlabs.lanshare.net.Scan
import dev.khanlabs.lanshare.net.SendItem
import dev.khanlabs.lanshare.net.SendJob
import dev.khanlabs.lanshare.net.ShareDownload
import dev.khanlabs.lanshare.net.ShareFile
import dev.khanlabs.lanshare.net.ShareServer
import dev.khanlabs.lanshare.net.fetchInfo
import dev.khanlabs.lanshare.protocol.ConnectCode
import dev.khanlabs.lanshare.protocol.DeviceId
import dev.khanlabs.lanshare.protocol.V1
import dev.khanlabs.lanshare.store.Stores
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One incoming send, as the screens show it. */
data class IncomingView(
    val id: String,
    val senderName: String,
    /** From the offer body: not proof of anything (PROTOCOL.md section 7). */
    val senderShortId: String,
    val ip: String,
    val fileCount: Int,
    val total: Long,
    val received: Long,
    val text: String,
    /** waiting, accepted, declined, expired, cancelled, done */
    val state: String,
    val saved: List<String>,
    val failed: Int,
    val firstNames: List<String>,
    /** When the offer arrived and when it lapses if nobody answers, in milliseconds since 1970. */
    val created: Long,
    val expiresAt: Long,
    /** Files (and their size) already in the receive folder, found when the offer came in. Only the sender's `skip_ok` fills this. */
    val alreadyHere: Int = 0,
    val alreadyBytes: Long = 0,
    /** Files left out because the person kept "skip" on. */
    val skipped: Int = 0,
)

/** One outgoing send, as the screens show it. */
data class OutgoingView(
    val key: Long,
    val deviceName: String,
    val state: SendJob.State,
    val message: String,
    val fileCount: Int,
    val sent: Long,
    val total: Long,
    val speed: Double,
    val etaSeconds: Long?,
    val current: String,
    val text: String,
    /** Files the other device already had and left out of this send. */
    val skipped: Int = 0,
    /** Files saved on the other side so far, and how many of them it confirmed with the same SHA-256. */
    val savedCount: Int = 0,
    val verified: Int = 0,
    /** The connection broke and the send waits to try again: the card shows "Try now". */
    val reconnecting: Boolean = false,
    /** Files that did not get across and are not already there: what "Send the rest" would send. */
    val unsent: Int = 0,
    /** Addresses (as text) to draw small pictures from: the first photos and videos of the send (see [Previews]). */
    val previews: List<String> = emptyList(),
)

/** The share to someone without Amanah, as the screens show it. Null in [UiState.share] when none is set up. */
data class ShareView(
    /** The address in the QR code. Empty when the phone has no Wi-Fi address right now. */
    val url: String,
    val pin: String?,
    /** Files and folders on the page. */
    val items: Int,
    val bytes: Long,
    val live: Boolean,
    val visitors: Int,
    val downloads: Int,
    val sent: Long,
    /** When the time limit runs out, in milliseconds since 1970; null means until it is stopped. */
    val endsAt: Long?,
    /** The page offers Amanah for Android. */
    val installer: Boolean,
    /** All files, and how many are ready to download, still being checked, or could not be read. */
    val files: Int = 0,
    val ready: Int = 0,
    val preparing: Int = 0,
    val failed: Int = 0,
    /** Files where a download was cut because the file changed after it was checked. */
    val problems: Int = 0,
    /** The files that could not be read, with the reason; at most 20. */
    val failedFiles: List<ShareFileIssue> = emptyList(),
    /** The Wi-Fi this phone is connected to, when it says; null when it is not on Wi-Fi. */
    val link: WifiLink? = null,
)

class ShareFileIssue(val name: String, val reason: String)

/** How fast the phone's Wi-Fi connection is: [mhz] is the radio frequency (2412 to 2484 is 2.4 GHz), [mbps] the link speed. */
data class WifiLink(val mhz: Int, val mbps: Int) {
    val slowBand: Boolean get() = mhz in 1..2999
}

data class UiState(
    val name: String = "",
    val receiving: Boolean = true,
    val id: String = "",
    val shortId: String = "",
    val port: Int? = null,
    val addresses: List<String> = emptyList(),
    val listening: Boolean = false,
    val listenError: String? = null,
    val nearby: List<Device> = emptyList(),
    val added: List<Device> = emptyList(),
    val incoming: List<IncomingView> = emptyList(),
    val outgoing: List<OutgoingView> = emptyList(),
    val conformanceAllowed: Boolean = false,
    val conformance: Boolean = false,
    /** Where received files go, in words: Download/KhanLabs, or the folder the person chose. */
    val folder: String = Stores.SHOWN_PATH,
    /** The person chose a folder but Android no longer lets the app write in it; files go to Download/KhanLabs until it is chosen again. */
    val folderLost: Boolean = false,
    /** Whether the folder in [folder] is one the person chose (Settings shows "Use Download/KhanLabs"). */
    val folderChosen: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    /** Every text in every conversation, oldest first. */
    val messages: List<ChatMessage> = emptyList(),
    val share: ShareView? = null,
    /** The Wi-Fi network this phone made or joined because there was no router. */
    val ownWifi: OwnWifiView = OwnWifiView(),
    /** The Settings switch: also connect to the addresses VPNs hand out (100.64.0.0/10). */
    val allowVpn: Boolean = false,
    /** The Settings choice of which tab the app shows when it opens: a tab's name, "Send" by default. */
    val startTab: String = "Send",
    /** The Settings choice of look: system, light, dark or black. */
    val theme: String = "system",
    /** The Settings choice of accent color: teal, blue, violet, pink or mono. */
    val accent: String = "teal",
    /** IDs of the saved devices ([added]) that answered the last check, so they count as nearby. */
    val reachable: Set<String> = emptySet(),
    /** The Settings switch: list saved devices that are not nearby, under their own heading, so they can be removed. */
    val showInactive: Boolean = true,
    /** The Settings switch: a short buzz when a transfer ends. On by default; the phone's own touch feedback setting still applies. */
    val haptics: Boolean = true,
    /** The Settings switch: start receiving when the phone starts (see [BootStart]). Off until the person turns it on. */
    val bootStart: Boolean = BootStart.DEFAULT,
    /** The Settings choice of turning receiving off by itself: never, hour or network (see [AutoOff]). */
    val autoOff: String = AutoOff.NEVER,
    /** Why receiving is off when it turned itself off, in words for the status card; empty once it is turned on again. */
    val autoOffReason: String = "",
    /** Debug builds only: text size multiplier for large-font testing; 1 in release builds. */
    val debugFontScale: Float = 1f,
    /** Debug builds only: lay the screens out as on a 320 dp wide phone. */
    val debugSmallScreen: Boolean = false,
) {
    /** Devices to send to now: found on the network, or saved and answering. */
    val active: List<Device> get() = nearby + added.filter { it.id in reachable }

    /** Saved devices that did not answer. Only [showInactive] decides whether the screens list them. */
    val inactive: List<Device> get() = added.filter { it.id !in reachable }
}

/**
 * The app's one transfer engine: the receiver, discovery and send jobs. Screens read [state] and call the
 * functions here; the foreground service keeps the process alive while receiving is on.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object Engine : ReceiverEvents {
    private lateinit var app: Context
    lateinit var identity: Identity
        private set
    var appLabel = "Amanah Android"
        private set
    private var debugBuild = false
    private val clients = Clients(allowVpn = { prefs.getBoolean("allow_vpn", false) })
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "klshare-io").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val keys = AtomicLong()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var receiver: Receiver? = null
    private var listenError: String? = null
    private var discovery: Discovery? = null
    @Volatile private var nearby: List<Device> = emptyList()
    private val added = ArrayList<Device>()
    private val offers = LinkedHashMap<String, Offer>()
    private val jobs = LinkedHashMap<Long, SendJob>()
    /** The pictures to show on each send's card, by the same key as [jobs]. */
    private val previews = HashMap<Long, List<String>>()
    private var addresses: List<String> = emptyList()
    private var conformance = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var share: ShareServer? = null
    private var shareEndsAt: Long? = null
    private lateinit var own: OwnWifi
    @Volatile private var serviceWanted = false

    private val prefs by lazy { app.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    // no_backup, like the identity: a restore on another phone must not bring this phone's activity along
    private val history by lazy { History(File(app.noBackupFilesDir, "history.json")) }
    private val chats by lazy { ChatStore(File(app.noBackupFilesDir, "chats.jsonl")) }
    private val errors by lazy { ErrorLog(File(app.noBackupFilesDir, "errors.log")) }
    /** The conversation on screen right now, so a text for it doesn't also raise a notification. */
    @Volatile private var viewing: String? = null

    val isInitialized: Boolean get() = ::app.isInitialized

    /** Longest message the screens accept: 16,000 characters is at most 64,000 bytes, under the protocol's 64 KB. */
    const val MAX_MESSAGE_CHARS = 16_000

    /** Where a share keeps private copies of files of unknown size while it runs. Emptied at every start. */
    private const val STASH_DIR = "share-stash"

    fun init(context: Context, versionName: String, debug: Boolean) {
        if (isInitialized) return
        app = context.applicationContext
        appLabel = "Amanah Android $versionName"
        debugBuild = debug
        // no_backup: a phone restored from a backup must not get this phone's device ID
        identity = Identity.loadOrCreate(File(app.noBackupFilesDir, "identity"), "Android")
        File(app.noBackupFilesDir, STASH_DIR).deleteRecursively() // copies left by a share the system killed
        loadAdded()
        own = OwnWifi(app, main) { addresses = Discovery.lanAddresses(); refresh() }
        discovery = Discovery(app, identity.id, ::answers) { found -> nearby = found; refresh() }
        watchNetwork()
        addresses = Discovery.lanAddresses()
        autoOffBaseline = addresses
        scheduleAutoOff()
        recheckSaveFolder()
        refresh()
        checkSaved()
    }

    // --- the save folder

    @Volatile private var saveFolder = Stores.SHOWN_PATH
    @Volatile private var saveFolderLost = false
    @Volatile private var saveFolderChosen = false

    /**
     * Reads again which folder files go to and whether the chosen one still works. It asks the folder's provider, so it
     * runs off the main thread; the screens get the answer through [state]. Call it when the app comes to the front.
     */
    fun recheckSaveFolder() {
        io.execute {
            saveFolder = Stores.label(app)
            saveFolderLost = Stores.folderLost(app)
            saveFolderChosen = Stores.chosenTree(app) != null
            refresh()
        }
    }

    /**
     * Saves files in the folder the system folder picker returned, or in Download/KhanLabs when [tree] is null.
     * [onResult] is called on the main thread with words for the problem, or null when it worked.
     */
    fun setSaveFolder(tree: Uri?, onResult: (String?) -> Unit) {
        io.execute {
            val problem = Stores.choose(app, tree)
            saveFolder = Stores.label(app)
            saveFolderLost = Stores.folderLost(app)
            saveFolderChosen = Stores.chosenTree(app) != null
            refresh()
            main.post { onResult(problem) }
        }
    }

    // --- settings

    val name: String get() = prefs.getString("name", null)?.takeIf { it.isNotBlank() } ?: defaultName()
    val receiving: Boolean get() = prefs.getBoolean("receiving", true)

    /** Whether the range VPNs hand out (100.64.0.0/10) counts as the local network. Off unless the person turns it on. */
    val allowVpn: Boolean get() = prefs.getBoolean("allow_vpn", false)

    fun setAllowVpn(on: Boolean) {
        prefs.edit().putBoolean("allow_vpn", on).apply()
        refresh()
    }

    /** The tab the app shows when it opens, as the tab's name. The screen falls back to Send for a name it does not know. */
    val startTab: String get() = prefs.getString("start_tab", null) ?: "Send"

    /** The look: system, light, dark or black. */
    val theme: String get() = prefs.getString("theme", null) ?: "system"

    fun setTheme(value: String) {
        prefs.edit().putString("theme", value).apply()
        refresh()
    }

    /** The accent color's key: teal, blue, violet, pink or mono. */
    val accent: String get() = prefs.getString("accent", null) ?: "teal"

    fun setAccent(value: String) {
        prefs.edit().putString("accent", value).apply()
        refresh()
    }

    /** Whether the screens buzz when a transfer ends. */
    val haptics: Boolean get() = prefs.getBoolean("haptics", true)

    fun setHaptics(on: Boolean) {
        prefs.edit().putBoolean("haptics", on).apply()
        refresh()
    }

    /** Whether receiving starts by itself when the phone starts. Off until the person turns it on in Settings. */
    val bootStart: Boolean get() = prefs.getBoolean("boot_start", BootStart.DEFAULT)

    fun setBootStart(on: Boolean) {
        prefs.edit().putBoolean("boot_start", on).apply()
        refresh()
    }

    /** Writes a line to the error log that "Copy error report" gives, for a problem outside the Engine (a receiver, a service). */
    fun noteProblem(text: String) {
        errors.add(text)
    }

    fun setStartTab(tab: String) {
        prefs.edit().putString("start_tab", tab).apply()
        refresh()
    }

    private fun defaultName(): String {
        val set = if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) else null
        return (set ?: "${Build.MANUFACTURER} ${Build.MODEL}").trim().take(63)
    }

    fun setName(value: String) {
        prefs.edit().putString("name", value.trim().take(63)).apply()
        announce()
        refresh()
    }

    fun setReceiving(on: Boolean) = applyReceiving(on, "")

    /**
     * Turns receiving on or off. [reason] is the words for the status card when receiving turned itself off ([AutoOff]);
     * it is kept until receiving is turned on again. The hour of [AutoOff.HOUR] counts from the moment of turning it on.
     */
    private fun applyReceiving(on: Boolean, reason: String) {
        prefs.edit().putBoolean("receiving", on).putLong("rx_on_at", if (on) System.currentTimeMillis() else 0L)
            .putString("rx_off_reason", if (on) "" else reason).apply()
        // The service may keep running for a share; the receiver itself follows this switch.
        if (!on) stopReceiving()
        if (on) Notifications.cancelReceivingOff(app) else if (reason.isNotEmpty()) Notifications.receivingOff(app, reason)
        scheduleAutoOff()
        refresh()
    }

    // --- turning receiving off by itself

    /** The Settings choice: [AutoOff.NEVER] (the default), [AutoOff.HOUR] or [AutoOff.NETWORK]. */
    val autoOff: String get() = AutoOff.parse(prefs.getString("auto_off", null))

    fun setAutoOff(mode: String) {
        prefs.edit().putString("auto_off", AutoOff.parse(mode)).apply()
        scheduleAutoOff()
        refresh()
    }

    private val autoOffTask = Runnable { if (!turnOffIfHourUp()) scheduleAutoOff() }

    /** When receiving was turned on, in milliseconds since 1970. A phone that has had receiving on since before this existed counts from now. */
    private fun receivingSince(): Long {
        val now = System.currentTimeMillis()
        val since = prefs.getLong("rx_on_at", 0L)
        if (since in 1..now) return since
        prefs.edit().putLong("rx_on_at", now).apply() // never set, or the clock was set back: count from now
        return now
    }

    /** Sets (or clears) the timer for [AutoOff.HOUR]. */
    private fun scheduleAutoOff() {
        main.removeCallbacks(autoOffTask)
        if (receiving && autoOff == AutoOff.HOUR) {
            main.postDelayed(autoOffTask, AutoOff.hourLeft(receivingSince(), System.currentTimeMillis()))
        }
    }

    /**
     * Turns receiving off when the choice is [AutoOff.HOUR] and the hour is up. The timer does not run while the phone
     * sleeps, so this also runs on every state change; both only look at the clock. True when it turned receiving off.
     */
    private fun turnOffIfHourUp(): Boolean {
        if (!receiving || autoOff != AutoOff.HOUR) return false
        if (!AutoOff.hourUp(AutoOff.HOUR, receivingSince(), System.currentTimeMillis())) return false
        applyReceiving(false, AutoOff.reason(AutoOff.HOUR))
        return true
    }

    /** The local addresses the last time the Wi-Fi was looked at while this phone was not making or joining a network of its own. */
    @Volatile private var autoOffBaseline: List<String> = emptyList()

    /**
     * [AutoOff.NETWORK]: the phone's addresses moved, so it is on another Wi-Fi or has lost it. While this phone makes or
     * joins a network because the person asked for it, the addresses move for a reason the person chose, so nothing
     * happens and the baseline stays where it was.
     */
    private fun turnOffIfNetworkMoved(now: List<String>) {
        if (::own.isInitialized && own.view.active) return
        val before = autoOffBaseline
        autoOffBaseline = now
        if (receiving && AutoOff.networkMoved(autoOff, before, now)) applyReceiving(false, AutoOff.reason(AutoOff.NETWORK))
    }

    /** True while a share to anyone is handing files out. */
    val sharing: Boolean get() = synchronized(lock) { share }?.live() == true

    /**
     * The foreground service must run: receiving is on, a share is live, a Wi-Fi network is made or joined, or a send or
     * an accepted receive is under way (see [ServiceNeed]).
     */
    val needsService: Boolean get() =
        ServiceNeed.needed(receiving, sharing, ::own.isInitialized && own.view.active, transferring(_state.value))

    private fun transferring(s: UiState) = ServiceNeed.transferring(s.outgoing.map { it.state }, s.incoming.map { it.state })

    /** Debug builds only: scales text so large-font layouts can be tested on phones that won't change the system size. */
    fun setDebugFontScale(scale: Float) {
        if (debugBuild) prefs.edit().putFloat("debug_font_scale", scale).apply()
        refresh()
    }

    fun setDebugSmallScreen(on: Boolean) {
        if (debugBuild) prefs.edit().putBoolean("debug_small_screen", on).apply()
        refresh()
    }

    /** Debug builds only: answer offers by the sender's name, for the conformance tests. */
    fun setConformance(on: Boolean) {
        conformance = on && debugBuild
        refresh()
    }

    // --- receiving (run by TransferService)

    /** Starts the receiver and the announcement. Call from a worker thread. Returns false if no port was free. */
    fun startReceiving(): Boolean {
        if (!receiving) return true // the service may be running only for a share
        synchronized(lock) {
            if (receiver != null) return true
            try {
                val rx = Receiver(identity, { ReceiverSettings(name, receiving) }, { Stores.current(app) }, this, appLabel,
                    peeker = SocketPeek, messageTrusted = ::trustsMessage)
                rx.start()
                receiver = rx
                listenError = null
            } catch (e: Exception) {
                listenError = "Couldn't open a port for receiving: ${e.message}"
                refresh()
                return false
            }
        }
        main.post {
            announce()
            discovery?.startBrowsing()
        }
        refresh()
        return true
    }

    fun stopReceiving() {
        val rx = synchronized(lock) { receiver.also { receiver = null } }
        main.post {
            discovery?.unannounce()
            discovery?.stopBrowsing()
        }
        io.execute { rx?.stop() }
        refresh()
    }

    private fun announce() {
        val rx = receiver
        if (rx == null || !receiving) return
        discovery?.announce(Announcement(identity.id, name, rx.port, appLabel))
    }

    /** The Refresh button: looks for devices again, and asks each saved device whether it answers. */
    fun refreshNearby() {
        main.post { discovery?.refresh() }
        checkSaved()
    }

    /** IDs of saved devices that answered the last check. A device added just now answered when it was added. */
    @Volatile private var reachable: Set<String> = emptySet()
    private var checking = false
    private var lastChecked = System.nanoTime() - 3_600_000_000_000L

    /**
     * Asks every saved device (added by address, QR code or search) whether it answers, and keeps the answer in
     * [UiState.reachable]. Runs in the background, a device at a time in parallel, and does nothing while a check runs.
     */
    fun checkSaved(minGapMs: Long = 0) {
        val list = synchronized(lock) {
            if (checking || System.nanoTime() - lastChecked < minGapMs * 1_000_000) return
            checking = true
            added.toList()
        }
        io.execute {
            try {
                val answered = list.map { d -> d.id to io.submit<Boolean> { answers(d) } }
                    .filter { (_, f) -> try { f.get() } catch (e: Exception) { false } }
                    .map { it.first }.toSet()
                // a device added while this ran was not asked, and keeps the answer it gave when it was added
                reachable = (reachable - list.map { it.id }.toSet()) + answered
            } finally {
                synchronized(lock) { checking = false; lastChecked = System.nanoTime() }
                refresh()
            }
        }
    }

    private fun noteReachable(ids: Collection<String>) {
        reachable = reachable + ids
    }

    /** Whether the lists show saved devices that are not nearby. On unless the person turned it off. */
    val showInactive: Boolean get() = prefs.getBoolean("show_inactive", true)

    fun setShowInactive(on: Boolean) {
        prefs.edit().putBoolean("show_inactive", on).apply()
        refresh()
    }

    /** Forgets every saved device that did not answer the last check. Devices found on the network are not touched. */
    fun forgetInactive() {
        val gone = state.value.inactive.map { it.id }.toSet()
        synchronized(lock) { added.removeAll { it.id in gone } }
        saveAdded()
        refresh()
    }

    /**
     * The person's answer to an incoming send. [skip] leaves out the files already here; it is on by default, like the
     * checkbox on the offer, so Accept in the notification does the same as Accept on the sheet.
     */
    fun answer(offerId: String, accept: Boolean, skip: Boolean = true) {
        io.execute { receiver?.answer(offerId, accept, skip) }
        Notifications.cancelOffer(app, offerId)
    }

    fun cancelIncoming(offerId: String) {
        io.execute { receiver?.cancel(offerId) }
    }

    fun dismissIncoming(offerId: String) {
        synchronized(lock) { offers.remove(offerId) }
        Notifications.cancelOffer(app, offerId)
        refresh()
    }

    override fun onOffer(offer: Offer) {
        synchronized(lock) { offers[offer.id] = offer }
        if (conformance) {
            val n = offer.sender.name
            if ("[wait]" !in n) io.execute { receiver?.answer(offer.id, "[decline]" !in n, "[skip]" in n) }
        } else {
            Notifications.offer(app, view(offer))
        }
        refresh()
    }

    override fun onFile(offer: Offer, file: IncomingFile) = refresh()

    override fun onProgress(offer: Offer, file: IncomingFile) = refresh()

    override fun onFinished(offer: Offer) {
        Notifications.cancelOffer(app, offer.id)
        if (offer.state == "done" && offer.files.isEmpty() && offer.text.isNotEmpty()) {
            // a text on its own is a message: it goes into the conversation, not the activity list
            synchronized(lock) { offers.remove(offer.id) }
            receiveMessage(offer.sender.id, offer.sender.name, offer.ip, offer.text)
            refresh()
            return
        }
        val v = view(offer)
        // every file was already here and nothing else came with it: the card says so, no notification or history entry
        val nothingNew = offer.files.isNotEmpty() && offer.files.all { it.skipped } && offer.text.isEmpty()
        if (!conformance && !nothingNew && (offer.state == "done" || (offer.state == "cancelled" && v.saved.isNotEmpty()))) {
            Notifications.received(app, v)
        }
        if (!nothingNew && (offer.state == "done" || (offer.state == "cancelled" && v.saved.isNotEmpty()))) {
            val now = System.currentTimeMillis()
            history.add(HistoryEntry("in:${offer.id}", true, v.senderName, if (offer.state == "done") "done" else "partial",
                v.saved.size, v.received, now, v.saved.take(History.MAX_NAMES), v.text,
                peerId = if (DeviceId.isValid(offer.sender.id)) offer.sender.id else "",
                kind = if (v.saved.any { '/' in it }) "folder" else "files",
                requestedAt = offer.created, acceptedAt = offer.acceptedAt, endedAt = now,
                filesSkipped = offer.files.count { it.skipped }, bytesSkipped = offer.files.filter { it.skipped }.sumOf { it.size },
                items = offer.files.map { f ->
                    HistoryFile(f.savedName ?: f.name, f.size, when {
                        f.skipped -> "skipped"
                        f.state == "done" -> "done"
                        f.state == "failed" -> "failed"
                        else -> "cancelled" // still waiting or half received when the receive was stopped
                    })
                }))
        }
        refresh()
    }

    private fun view(o: Offer) = IncomingView(
        id = o.id,
        senderName = o.senderName,
        senderShortId = if (DeviceId.isValid(o.sender.id)) DeviceId.short(o.sender.id) else "",
        ip = o.ip,
        fileCount = o.files.size,
        total = o.total,
        received = o.received,
        text = o.text,
        state = o.state,
        saved = o.files.mapNotNull { it.savedName },
        failed = o.files.count { it.state == "failed" },
        firstNames = o.files.take(3).map { it.name },
        created = o.created,
        expiresAt = o.created + V1.OFFER_TIMEOUT_S * 1000,
        alreadyHere = o.already.size,
        alreadyBytes = o.already.sumOf { it.size },
        skipped = o.files.count { it.skipped },
    )

    // --- messages

    private fun devices(): List<Device> = nearby + synchronized(lock) { added.toList() }

    private fun deviceFor(peer: String): Device? = devices().firstOrNull { it.id == peer }

    /**
     * A chat text skips the Accept prompt only from a device this phone already talks to, and only from an address that
     * device announced: the ID in an offer proves nothing (PROTOCOL.md section 7).
     */
    private fun trustsMessage(offer: Offer): Boolean {
        val d = deviceFor(offer.sender.id.uppercase()) ?: return false
        return chats.knows(d.id) && offer.ip in d.hosts
    }

    override fun onMessage(offer: Offer) {
        receiveMessage(offer.sender.id, offer.sender.name, offer.ip, offer.text)
        refresh()
    }

    private fun receiveMessage(senderId: String, senderName: String, ip: String, text: String) {
        val all = devices()
        val peer = ChatStore.peerOf(senderId, ip, all)
        val name = (all.firstOrNull { it.id == peer }?.name ?: senderName.ifEmpty { ip }).take(63)
        val seen = viewing == peer
        chats.add(peer, name, incoming = true, text, read = seen)
        if (!seen && !conformance) Notifications.message(app, peer, name, chats.thread(peer))
    }

    /** The conversation on screen, or null when none is. Opening one marks it read. */
    fun setViewing(peer: String?) {
        viewing = peer
        if (peer != null) markRead(peer)
    }

    fun markRead(peer: String) {
        Notifications.cancelMessage(app, peer)
        if (chats.markRead(peer)) refresh()
    }

    /** Writes to a device: asks it to accept the first time, then arrives without asking. */
    fun sendMessage(device: Device, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val m = chats.add(device.id, device.name, incoming = false, body, state = "sending")
        refresh()
        deliver(device, m)
    }

    /** Sends a text that failed or was declined again. */
    fun retryMessage(id: String) {
        val m = chats.all.firstOrNull { it.id == id && !it.incoming && (it.state == "failed" || it.state == "declined") } ?: return
        val d = deviceFor(m.peer)
        if (d == null) {
            chats.setState(id, "failed", "Not nearby right now")
        } else {
            chats.setState(id, "sending")
            deliver(d, m)
        }
        refresh()
    }

    /** The Reply button on a message notification. */
    fun reply(peer: String, text: String) {
        val body = text.trim().take(MAX_MESSAGE_CHARS)
        if (body.isEmpty()) return
        val d = deviceFor(peer)
        if (d == null) {
            val name = chats.conversations().firstOrNull { it.peer == peer }?.name ?: ""
            chats.add(peer, name, incoming = false, body, state = "failed", note = "Not nearby right now")
        } else {
            chats.markRead(peer)
            sendMessage(d, body)
        }
        refresh()
        // posting again is what ends the spinner on the Reply button, and shows the reply in the thread
        Notifications.message(app, peer, d?.name ?: "", chats.thread(peer), alert = false)
    }

    fun forgetChat(peer: String) {
        Notifications.cancelMessage(app, peer)
        chats.forget(peer)
        refresh()
    }

    private fun deliver(device: Device, m: ChatMessage) {
        if (m.text.toByteArray(Charsets.UTF_8).size > V1.MAX_TEXT) {
            chats.setState(m.id, "failed", "Too long for a message (64 KB). Send it as a file instead.")
            return refresh()
        }
        val job = SendJob(clients, me(), device, emptyList(), m.text, V1.KIND_MESSAGE) { j ->
            when (j.state) {
                SendJob.State.DONE -> chats.setState(m.id, "done")
                SendJob.State.DECLINED -> chats.setState(m.id, "declined", j.message)
                SendJob.State.FAILED, SendJob.State.CANCELLED -> {
                    chats.setState(m.id, "failed", j.message)
                    if (j.state == SendJob.State.FAILED) logFailure("message to ${device.name}", j)
                }
                else -> {}
            }
            refresh()
        }
        io.execute { job.run() }
    }

    // --- sending

    private fun me() = Me(identity.id, name, "android", appLabel)

    /**
     * Opens files another app shared, right away and off the main thread, so the app can't take access back while the
     * person picks a device. Pass the result to [send] by way of its items, or call its release if nothing is sent.
     */
    fun holdFiles(uris: List<Uri>): HeldFiles {
        val result = CompletableDeferred<Pair<List<SendItem>, Int>>()
        io.execute {
            result.complete(try {
                Items.fromUris(app, uris, hold = true)
            } catch (e: Exception) {
                emptyList<SendItem>() to uris.size // none could be read
            })
        }
        return HeldFiles(result)
    }

    fun send(device: Device, items: List<SendItem>, text: String) {
        val key = keys.incrementAndGet()
        val job = SendJob(clients, me(), device, items, text) { refresh() }
        synchronized(lock) {
            jobs[key] = job
            previews[key] = Previews.of(items)
        }
        refresh()
        keepAliveForSend()
        io.execute {
            val begun = System.currentTimeMillis()
            job.run()
            // a failed or cancelled send keeps its files open for "Send the rest"; a finished or declined one is over
            if (job.state == SendJob.State.DONE || job.state == SendJob.State.DECLINED) job.releaseItems()
            record(job, begun)
            refresh()
        }
    }

    /**
     * Starts the foreground service for a send, so Android keeps the process when the app goes to the background.
     * A send begins from the screen, which is in front, so Android allows it; if it does not, the send goes on while
     * the app stays open.
     */
    private fun keepAliveForSend() = main.post {
        try {
            TransferService.start(app)
        } catch (e: Exception) {
            errors.add("couldn't start the service for a send: ${e.message}")
        }
    }

    private fun record(job: SendJob, begun: Long) {
        val state = when (job.state) {
            SendJob.State.DONE -> "done"
            SendJob.State.DECLINED -> "declined"
            SendJob.State.FAILED -> "failed"
            SendJob.State.CANCELLED -> "cancelled"
            else -> return
        }
        if (state == "failed") logFailure("send to ${job.device.name}", job)
        val count = job.items.size - job.skipped
        if (state == "done" && job.items.isNotEmpty() && count == 0) return // nothing new went across
        val now = System.currentTimeMillis()
        history.add(HistoryEntry("out:${job.offerId}", false, job.device.name, state, count,
            if (state == "done") job.total else job.sent, now,
            job.items.take(History.MAX_NAMES).map { it.name }, job.text, if (state == "failed") job.message else "",
            peerId = job.device.id,
            kind = if (job.items.isEmpty()) "text" else if (job.items.any { '/' in it.name }) "folder" else "files",
            route = if (nearby.any { it.id == job.device.id }) "nearby" else "saved",
            requestedAt = begun, acceptedAt = job.acceptedAt, endedAt = now,
            filesSkipped = job.skipped, bytesSkipped = job.skippedBytes,
            resumes = job.resumes, peakBps = job.peakSpeed.toLong(), verified = job.verified,
            items = sentFiles(job, state)))
    }

    /** Each file of a send with its size and what became of it; a file that did not get there is marked with how the send ended. */
    private fun sentFiles(job: SendJob, endState: String): List<HistoryFile> {
        val results = job.results()
        return job.items.mapIndexed { i, item ->
            HistoryFile(item.name, item.size, if (results[i] == SendJob.UNSENT) endState else results[i])
        }
    }

    private fun logFailure(what: String, job: SendJob) {
        val cause = job.failure?.let { " [${it.javaClass.simpleName}: ${it.message}]" }.orEmpty()
        errors.add("$what failed: ${job.message}$cause")
    }

    /**
     * The text behind "Copy error report". Nothing is sent anywhere: the screen puts it on the clipboard. It has device
     * names and may have file names, so the screen tells the person to read it first.
     */
    fun errorReport(): String {
        val s = _state.value
        val facts = listOf(
            "Receiving" to (if (s.receiving) "on" else "off") + (s.port?.let { ", port $it" } ?: "") +
                (s.listenError?.let { ", problem: $it" } ?: ""),
            "Addresses on this phone" to s.addresses.size.toString(),
            "Devices seen now" to "${s.nearby.size} nearby, ${s.added.size} added by address",
            "Share to anyone" to (if (s.share != null) "on" else "off"),
            "Own Wi-Fi network" to (if (s.ownWifi.hosting) "made by this phone" else "off"),
            "VPN addresses allowed" to (if (allowVpn) "yes" else "no"),
        )
        val system = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
        return ErrorReport.build(appLabel, system, facts, errors.tail(ErrorReport.LOG_LINES))
    }

    fun removeHistory(id: String) {
        history.remove(id)
        refresh()
    }

    fun clearHistory() {
        history.clear()
        refresh()
    }

    fun cancelSend(key: Long) {
        synchronized(lock) { jobs[key] }?.cancel()
    }

    /** "Try now" on a send that is waiting to try again. */
    fun retryNow(key: Long) {
        synchronized(lock) { jobs[key] }?.retryNow()
    }

    /**
     * "Send the rest": a new offer to the same device with what a failed or cancelled send did not get across. The old
     * card goes away. The text goes again only when nothing was accepted yet, so it is never shown twice.
     */
    fun sendRest(key: Long) {
        val job = synchronized(lock) { jobs[key] } ?: return
        val rest = job.unfinished()
        if (rest.isEmpty()) return
        val reached = job.sent > 0 || job.saved.isNotEmpty() || job.skipped > 0
        removeSend(key) // the files that did not get there stay open: the new send reads them
        send(job.device, rest, if (reached) "" else job.text)
    }

    fun dismissSend(key: Long) {
        removeSend(key)?.releaseItems()
    }

    private fun removeSend(key: Long): SendJob? {
        val job = synchronized(lock) {
            previews.remove(key)
            jobs.remove(key)
        }
        refresh()
        return job
    }

    /** True if the device answers on one of its addresses with the same ID. Blocks for up to 3 s per address. */
    private fun answers(device: Device): Boolean = device.hosts.any { host ->
        try {
            fetchInfo(clients, host, device.port, device.id, timeoutS = 3)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** "Add by IP": asks the address who it is, then keeps the device. Calls back on the main thread. */
    fun addByIp(address: String, onResult: (String?) -> Unit) {
        val host = address.trim().substringBefore(':')
        val port = address.trim().substringAfter(':', "").toIntOrNull()
        io.execute {
            var error: String? = Errors.addByIp(host, IOException("no answer"))
            var first: Exception? = null
            val ports = if (port != null) listOf(port) else (V1.DEFAULT_PORT until V1.DEFAULT_PORT + V1.PORT_TRIES).toList()
            for (p in ports) {
                try {
                    val d = fetchInfo(clients, host, p, null, timeoutS = 3)
                    if (d.id == identity.id) {
                        error = "That is this device"
                        break
                    }
                    synchronized(lock) {
                        added.removeAll { it.id == d.id }
                        added.add(d)
                    }
                    saveAdded()
                    noteReachable(listOf(d.id))
                    error = null
                    break
                } catch (e: Exception) {
                    if (Clients.mismatch(e) != null) { error = e.message; break }
                    // the first port tried says the most: a later one only says "nothing here"
                    if (first == null) first = e
                    error = Errors.addByIp(host, first ?: e)
                }
            }
            error?.let { errors.add("add by address $host failed: $it" + (first?.let { f -> " [${f.javaClass.simpleName}: ${f.message}]" } ?: "")) }
            refresh()
            main.post { onResult(error) }
        }
    }

    @Volatile private var searchStop = false

    /**
     * "Search this network": asks the addresses near this phone's own whether an Amanah device answers, for networks
     * where announcements do not get through. Only when the person taps it. Devices found are kept like ones added by
     * address. Both callbacks run on the main thread; [onDone] gets the devices found (new or already known) or a message.
     */
    fun searchNetwork(onProgress: (done: Int, total: Int) -> Unit, onDone: (found: List<Device>, error: String?) -> Unit) {
        searchStop = false
        io.execute {
            val mine = (own.view.addresses + addresses).distinct()
            val hosts = Scan.hosts(mine)
            if (hosts.isEmpty()) {
                main.post { onDone(emptyList(), "This phone isn't on a Wi-Fi network, so there is nothing to search.") }
                return@execute
            }
            var last = 0L
            val found = Scan.run(clients, mine, identity.id, stop = { searchStop }, progress = { done, total ->
                val now = System.nanoTime()
                if (done == total || now - last > 150_000_000L) {
                    last = now
                    main.post { onProgress(done, total) }
                }
            })
            if (found.isNotEmpty()) {
                synchronized(lock) {
                    for (d in found) {
                        added.removeAll { it.id == d.id }
                        added.add(d)
                    }
                }
                saveAdded()
                noteReachable(found.map { it.id })
                refresh()
            }
            main.post { onDone(found, null) }
        }
    }

    fun stopSearch() {
        searchStop = true
    }

    /**
     * Adds the device a scanned QR code names. Unlike "Add by IP" the full ID is pinned: the device that answers must
     * hold the certificate the code names. Calls back on the main thread with the device, or with a message.
     */
    fun addByCode(code: ConnectCode, onResult: (device: Device?, error: String?) -> Unit) {
        io.execute {
            var device: Device? = null
            var error: String? = "Nothing answered. Check that both devices are on the same Wi-Fi."
            if (code.id == identity.id) {
                error = "That is this device."
            } else for (host in code.hosts) {
                try {
                    val d = fetchInfo(clients, host, code.port, code.id, timeoutS = 3)
                    val found = d.copy(hosts = listOf(host) + code.hosts.filter { it != host })
                    synchronized(lock) {
                        added.removeAll { it.id == found.id }
                        added.add(found)
                    }
                    saveAdded()
                    noteReachable(listOf(found.id))
                    device = found
                    error = null
                    break
                } catch (e: Exception) {
                    if (Clients.mismatch(e) != null) {
                        error = "The device at that address is not the one in the QR code. The code may be out of date."
                        break
                    }
                }
            }
            refresh()
            main.post { onResult(device, error) }
        }
    }

    /** This device's own connect code, for showing as a QR code. Null until it is listening on a Wi-Fi address. */
    fun myCode(): ConnectCode? {
        val s = state.value
        val port = s.port ?: return null
        val o = s.ownWifi
        val hosts = (o.addresses + s.addresses).filter { ConnectCode.isLocalAddress(it) }.distinct()
        if (hosts.isEmpty()) return null
        // on a network this phone made, the code also carries its name and password: one scan joins and connects
        val wifi = o.hosting && o.addresses.isNotEmpty()
        return ConnectCode(identity.id, s.name, hosts.take(ConnectCode.MAX_HOSTS), port, if (wifi) o.ssid else "", if (wifi) o.psk else "")
    }

    fun forget(device: Device) {
        synchronized(lock) { added.removeAll { it.id == device.id } }
        saveAdded()
        refresh()
    }

    private fun saveAdded() {
        val list = synchronized(lock) { added.toList() }
        val json = buildJsonArray {
            for (d in list) add(buildJsonObject {
                put("id", d.id); put("name", d.name); put("model", d.model); put("port", d.port); put("app", d.app)
                put("hosts", JsonArray(d.hosts.map { JsonPrimitive(it) }))
            })
        }
        prefs.edit().putString("added", json.toString()).apply()
    }

    private fun loadAdded() {
        try {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(prefs.getString("added", "[]")!!).jsonArray
            for (e in arr) {
                val o = e.jsonObject
                added += Device(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content,
                    o["model"]!!.jsonPrimitive.content, o["hosts"]!!.jsonArray.map { it.jsonPrimitive.content },
                    o["port"]!!.jsonPrimitive.int, o["app"]?.jsonPrimitive?.content ?: "")
            }
        } catch (e: Exception) {
            // a broken list is dropped
        }
    }

    // --- own Wi-Fi network (no router)

    /** Makes a Wi-Fi network on this phone. The screen asks for the permission first. The result shows in [UiState.ownWifi]. */
    fun startOwnNetwork(band: OwnBand = OwnBand.Auto) {
        main.post {
            own.startHosting(band)
            TransferService.start(app)
        }
    }

    /** Whether this phone can make its network on a band the person picks, and whether it has 5 GHz at all. */
    val bandSupport: BandSupport get() = if (::own.isInitialized) own.bandSupport else BandSupport(canPick = false, has5Ghz = false)

    fun stopOwnNetwork() {
        main.post { own.stopAll() }
    }

    /** Joins a network for this app only (Android asks the person), then calls back on the main thread: null, or a message. */
    fun joinNetwork(ssid: String, psk: String, onResult: (String?) -> Unit) {
        main.post {
            TransferService.start(app)
            own.join(ssid, psk, onResult)
        }
    }

    /** Lets go of a joined network. */
    fun leaveNetwork() {
        main.post { own.leave() }
    }

    /** What a scanned code with a network in it asks for: join the network, then add the device. */
    fun joinAndConnect(code: ConnectCode, onResult: (device: Device?, error: String?) -> Unit) {
        joinNetwork(code.ssid, code.psk) { error -> if (error != null) onResult(null, error) else addByCode(code, onResult) }
    }

    // --- share to anyone

    /** The address a phone on this Wi-Fi would use, or null when this phone has none. On its own network, that network's address. */
    private fun shareHost(): String? =
        (own.view.addresses + addresses).firstOrNull { ConnectCode.isLocalAddress(it) }

    /** Amanah for Android itself, offered on the page. Not offered when Android split the install into several files. */
    private fun shareDownloads(): List<ShareDownload> {
        val info = app.applicationInfo
        val apk = File(info.sourceDir)
        if (!apk.isFile || !info.splitSourceDirs.isNullOrEmpty()) return emptyList()
        return listOf(ShareDownload("Amanah for Android", "Amanah.apk", apk.length()) { FileInputStream(apk) })
    }

    /**
     * Starts handing [items] to anyone who scans the QR code. [minutes] 0 means until stopped. Calls back on the main
     * thread with null, or a message the screen shows.
     */
    fun startShare(items: List<SendItem>, usePin: Boolean, minutes: Int, onResult: (String?) -> Unit) {
        io.execute {
            val error = try {
                when {
                    items.isEmpty() -> "Pick something to share first."
                    synchronized(lock) { share != null } -> "Something is already being shared. Stop it first."
                    shareHost() == null -> "Connect to Wi-Fi first. People can only open this on the same Wi-Fi."
                    else -> {
                        val pin = if (usePin) "%06d".format(SecureRandom().nextInt(1_000_000)) else null
                        val link = if (receiving) myCode()?.toText().orEmpty() else ""
                        val s = ShareServer(items, pin, minutes, shareDownloads(), link, appLabel, onChange = ::refresh,
                            stash = File(app.noBackupFilesDir, STASH_DIR))
                        try {
                            s.start()
                        } catch (e: Exception) {
                            s.close()
                            throw e
                        }
                        synchronized(lock) {
                            share = s
                            shareEndsAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else null
                        }
                        null
                    }
                }
            } catch (e: Exception) {
                "Couldn't start sharing: ${e.message}"
            }
            refresh()
            if (error == null) main.post { TransferService.start(app) }
            main.post { onResult(error) }
        }
    }

    /**
     * Adds [items] to the live share. Everything shared so far stays as it is; the page shows the new files at the
     * top as just added. Calls back on the main thread with null, or a message the screen shows.
     */
    fun addToShare(items: List<SendItem>, onResult: (String?) -> Unit) {
        io.execute {
            val s = synchronized(lock) { share }
            val error = try {
                when {
                    items.isEmpty() -> "Pick something to add first."
                    s == null || !s.live() -> "This share has ended. Start a new one."
                    else -> {
                        s.addFiles(items)
                        null
                    }
                }
            } catch (e: IllegalStateException) {
                "This share has ended. Start a new one."
            } catch (e: IllegalArgumentException) {
                e.message ?: "Couldn't add those files."
            } catch (e: Exception) {
                "Couldn't add those files: ${e.message}"
            }
            refresh()
            main.post { onResult(error) }
        }
    }

    /** Takes the files that could not be read out of the share, so they can be picked again. */
    fun removeUnreadableFromShare() {
        val s = synchronized(lock) { share } ?: return
        io.execute { s.removeFailed() }
    }

    /** Ends the share and clears it from the screen. Downloads in progress are cut; the address then says it has ended. */
    fun stopShare() {
        val s = synchronized(lock) { share.also { share = null; shareEndsAt = null } } ?: return
        io.execute { s.end() }
        refresh()
    }

    /** The service is going away: nothing can be served after this. A share that already ended on its own stays on screen. */
    fun shareServiceGone() {
        val s = synchronized(lock) { share } ?: return
        if (s.live()) stopShare()
    }

    private fun shareView(): ShareView? {
        val s = share ?: return null
        val st = s.stats()
        return ShareView(
            url = shareHost()?.let { s.url(it) }.orEmpty(), pin = s.pin, items = s.itemCount, bytes = s.totalBytes,
            live = st.live, visitors = st.visitors, downloads = st.downloads, sent = st.bytes, endsAt = shareEndsAt,
            installer = s.hasInstaller,
            files = st.files, ready = st.ready, preparing = st.preparing, failed = st.failed, problems = st.problems,
            failedFiles = if (st.failed == 0) emptyList()
            else s.sharedFiles.filter { it.state == ShareFile.State.FAILED }.take(20).map { ShareFileIssue(it.name, it.error) },
            link = wifiLink(),
        )
    }

    /** The phone's own Wi-Fi connection. Needs no location permission, because the name of the network is not read. */
    @Suppress("DEPRECATION")
    private fun wifiLink(): WifiLink? = try {
        val info = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        if (info == null || info.frequency <= 0) null else WifiLink(info.frequency, info.linkSpeed.coerceAtLeast(0))
    } catch (e: Exception) {
        null
    }

    // --- state

    private fun watchNetwork() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = networkChanged()
                override fun onLost(network: Network) = networkChanged()
                override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = networkChanged()
            })
        } catch (e: Exception) {
            // without the callback the addresses update on the next refresh
        }
    }

    private fun networkChanged() {
        addresses = Discovery.lanAddresses()
        turnOffIfNetworkMoved(addresses)
        refresh()
        checkSaved(minGapMs = 10_000)
    }

    /** Rebuilds the screen state from the live objects. Called from any thread. */
    fun refresh() {
        if (!isInitialized) return
        if (turnOffIfHourUp()) return // it calls refresh() again
        val snapshot = synchronized(lock) {
            UiState(
                name = name,
                receiving = receiving,
                id = identity.id,
                shortId = identity.shortId,
                port = receiver?.port,
                addresses = addresses,
                listening = receiver != null,
                listenError = listenError,
                nearby = nearby,
                added = added.filter { a -> nearby.none { it.id == a.id } },
                incoming = offers.values.reversed().map { view(it) },
                outgoing = jobs.entries.reversed().map { (k, j) ->
                    OutgoingView(k, j.device.name, j.state, j.message, j.items.size, j.sent, j.total, j.speed,
                        j.etaSeconds, j.current, j.text, j.skipped, j.saved.size, j.verified, j.reconnecting,
                        j.unfinished().size, previews[k].orEmpty())
                },
                conformanceAllowed = debugBuild,
                conformance = conformance,
                history = history.all,
                messages = chats.all,
                share = shareView(),
                ownWifi = if (::own.isInitialized) own.view else OwnWifiView(),
                allowVpn = allowVpn,
                haptics = haptics,
                bootStart = bootStart,
                autoOff = autoOff,
                autoOffReason = if (receiving) "" else prefs.getString("rx_off_reason", null).orEmpty(),
                folder = saveFolder,
                folderLost = saveFolderLost,
                folderChosen = saveFolderChosen,
                startTab = startTab,
                theme = theme,
                accent = accent,
                reachable = reachable,
                showInactive = showInactive,
                debugFontScale = if (debugBuild) prefs.getFloat("debug_font_scale", 1f) else 1f,
                debugSmallScreen = debugBuild && prefs.getBoolean("debug_small_screen", false),
            )
        }
        _state.value = snapshot
        holdLocks(snapshot)
        val need = ServiceNeed.needed(receiving, snapshot.share?.live == true, snapshot.ownWifi.active, transferring(snapshot))
        if (serviceWanted && !need) main.post { TransferService.stop(app) } // nothing left for the service to do
        serviceWanted = need
    }

    /** Keeps the CPU and Wi-Fi awake while something is moving, so a send survives the screen turning off. */
    @Suppress("DEPRECATION")
    private fun holdLocks(s: UiState) {
        val busy = s.share?.live == true || s.ownWifi.active || s.incoming.any { it.state == "accepted" } ||
            s.outgoing.any { it.state == SendJob.State.SENDING || it.state == SendJob.State.WAITING }
        synchronized(this) {
            if (busy && wakeLock == null) {
                wakeLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "klshare:transfer").apply {
                        setReferenceCounted(false)
                        acquire(6 * 3600 * 1000L)
                    }
                wifiLock = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "klshare:transfer").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            } else if (!busy && wakeLock != null) {
                wakeLock?.let { if (it.isHeld) it.release() }
                wifiLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                wifiLock = null
            }
        }
    }
}
