package dev.khanlabs.lanshare.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import dev.khanlabs.lanshare.link.Announcement
import dev.khanlabs.lanshare.link.Discovery
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
import dev.khanlabs.lanshare.net.ShareServer
import dev.khanlabs.lanshare.net.fetchInfo
import dev.khanlabs.lanshare.protocol.ConnectCode
import dev.khanlabs.lanshare.protocol.DeviceId
import dev.khanlabs.lanshare.protocol.V1
import dev.khanlabs.lanshare.store.Stores
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
)

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
    val folder: String = Stores.SHOWN_PATH,
    val history: List<HistoryEntry> = emptyList(),
    /** Every text in every conversation, oldest first. */
    val messages: List<ChatMessage> = emptyList(),
    val share: ShareView? = null,
    /** The Wi-Fi network this phone made or joined because there was no router. */
    val ownWifi: OwnWifiView = OwnWifiView(),
    /** The Settings switch: also connect to the addresses VPNs hand out (100.64.0.0/10). */
    val allowVpn: Boolean = false,
    /** Debug builds only: text size multiplier for large-font testing; 1 in release builds. */
    val debugFontScale: Float = 1f,
    /** Debug builds only: lay the screens out as on a 320 dp wide phone. */
    val debugSmallScreen: Boolean = false,
)

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

    fun init(context: Context, versionName: String, debug: Boolean) {
        if (isInitialized) return
        app = context.applicationContext
        appLabel = "Amanah Android $versionName"
        debugBuild = debug
        // no_backup: a phone restored from a backup must not get this phone's device ID
        identity = Identity.loadOrCreate(File(app.noBackupFilesDir, "identity"), "Android")
        loadAdded()
        own = OwnWifi(app, main) { addresses = Discovery.lanAddresses(); refresh() }
        discovery = Discovery(app, identity.id, ::answers) { found -> nearby = found; refresh() }
        watchNetwork()
        addresses = Discovery.lanAddresses()
        refresh()
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

    private fun defaultName(): String {
        val set = if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) else null
        return (set ?: "${Build.MANUFACTURER} ${Build.MODEL}").trim().take(63)
    }

    fun setName(value: String) {
        prefs.edit().putString("name", value.trim().take(63)).apply()
        announce()
        refresh()
    }

    fun setReceiving(on: Boolean) {
        prefs.edit().putBoolean("receiving", on).apply()
        // The service may keep running for a share; the receiver itself follows this switch.
        if (!on) stopReceiving()
        refresh()
    }

    /** True while a share to anyone is handing files out. */
    val sharing: Boolean get() = synchronized(lock) { share }?.live() == true

    /** The foreground service must run: receiving is on, a share is live, or a Wi-Fi network is made or joined. */
    val needsService: Boolean get() = receiving || sharing || (::own.isInitialized && own.view.active)

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
                val rx = Receiver(identity, { ReceiverSettings(name, receiving) }, { Stores.default(app) }, this, appLabel,
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

    fun refreshNearby() {
        main.post { discovery?.refresh() }
    }

    /** The person's answer to an incoming send. */
    fun answer(offerId: String, accept: Boolean) {
        io.execute { receiver?.answer(offerId, accept) }
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
            if ("[wait]" !in n) io.execute { receiver?.answer(offer.id, "[decline]" !in n) }
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
        if (!conformance && (offer.state == "done" || (offer.state == "cancelled" && v.saved.isNotEmpty()))) {
            Notifications.received(app, v)
        }
        if (offer.state == "done" || (offer.state == "cancelled" && v.saved.isNotEmpty())) {
            history.add(HistoryEntry("in:${offer.id}", true, v.senderName, if (offer.state == "done") "done" else "partial",
                v.saved.size, v.received, System.currentTimeMillis(), v.saved.take(History.MAX_NAMES), v.text))
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

    fun send(device: Device, items: List<SendItem>, text: String) {
        val key = keys.incrementAndGet()
        val job = SendJob(clients, me(), device, items, text) { refresh() }
        synchronized(lock) { jobs[key] = job }
        refresh()
        io.execute {
            job.run()
            record(job)
            refresh()
        }
    }

    private fun record(job: SendJob) {
        val state = when (job.state) {
            SendJob.State.DONE -> "done"
            SendJob.State.DECLINED -> "declined"
            SendJob.State.FAILED -> "failed"
            SendJob.State.CANCELLED -> "cancelled"
            else -> return
        }
        if (state == "failed") logFailure("send to ${job.device.name}", job)
        history.add(HistoryEntry("out:${job.offerId}", false, job.device.name, state, job.items.size,
            if (state == "done") job.total else job.sent, System.currentTimeMillis(),
            job.items.take(History.MAX_NAMES).map { it.name }, job.text, if (state == "failed") job.message else ""))
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

    fun dismissSend(key: Long) {
        synchronized(lock) { jobs.remove(key) }
        refresh()
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
    fun startOwnNetwork() {
        main.post {
            own.startHosting()
            TransferService.start(app)
        }
    }

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
                        val s = ShareServer(items, pin, minutes, shareDownloads(), link, appLabel, onChange = ::refresh)
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
        )
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
        refresh()
    }

    /** Rebuilds the screen state from the live objects. Called from any thread. */
    fun refresh() {
        if (!isInitialized) return
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
                        j.etaSeconds, j.current, j.text)
                },
                conformanceAllowed = debugBuild,
                conformance = conformance,
                history = history.all,
                messages = chats.all,
                share = shareView(),
                ownWifi = if (::own.isInitialized) own.view else OwnWifiView(),
                allowVpn = allowVpn,
                debugFontScale = if (debugBuild) prefs.getFloat("debug_font_scale", 1f) else 1f,
                debugSmallScreen = debugBuild && prefs.getBoolean("debug_small_screen", false),
            )
        }
        _state.value = snapshot
        holdLocks(snapshot)
        val need = receiving || snapshot.share?.live == true || snapshot.ownWifi.active
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
