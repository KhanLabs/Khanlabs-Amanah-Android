package dev.khanlabs.lanshare.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask

/** A copy of Amanah this device can hand over (its own APK): [label] on the button, [filename] when saved. */
class ShareDownload(val label: String, val filename: String, val size: Long, val open: () -> InputStream)

/** One thing the person picked: a file, or a whole folder with the paths of the files inside. */
class ShareItem(val folder: Boolean, val name: String, val files: List<SendItem>) {
    val size: Long = files.sumOf { it.size }
}

class ShareStats(val live: Boolean, val visitors: Int, val downloads: Int, val bytes: Long, val remainingMs: Long?)

/**
 * Wrong-PIN limits. One address gets 5 free tries, then is locked out for a minute, twice as long after each
 * further 5 (up to 15 minutes). All addresses together get 20 wrong tries in 10 minutes before the PIN is locked
 * for 15 minutes, so many addresses cannot share out the guesses. Matches the Windows app.
 */
class PinGate(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    enum class Result { OK, WRONG, LOCKED }

    private val lock = Any()
    private val state = HashMap<String, LongArray>() // ip -> [wrong count, locked until]
    private val recent = ArrayDeque<Long>()
    private var lockedUntil = 0L

    fun waitMs(ip: String): Long = synchronized(lock) {
        maxOf(lockedUntil - clock(), state[ip]?.let { it[1] - clock() } ?: 0L, 0L)
    }

    fun check(ip: String, pin: String, expected: String): Result = synchronized(lock) {
        val now = clock()
        if (now < lockedUntil || (state[ip]?.let { now < it[1] } == true)) return Result.LOCKED
        if (MessageDigest.isEqual(pin.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))) {
            state.remove(ip)
            return Result.OK
        }
        val st = state.getOrPut(ip) { longArrayOf(0, 0) }
        st[0]++
        if (st[0] % FREE_TRIES == 0L) {
            val rounds = (st[0] / FREE_TRIES).toInt()
            st[1] = now + minOf(BASE_LOCK_MS shl (rounds - 1).coerceAtMost(20), MAX_LOCK_MS)
        }
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > GLOBAL_WINDOW_MS) recent.removeFirst()
        if (recent.size >= GLOBAL_WRONG) {
            lockedUntil = now + GLOBAL_LOCK_MS
            recent.clear()
        }
        Result.WRONG
    }

    private companion object {
        const val FREE_TRIES = 5L
        const val BASE_LOCK_MS = 60_000L
        const val MAX_LOCK_MS = 15 * 60_000L
        const val GLOBAL_WRONG = 20
        const val GLOBAL_WINDOW_MS = 10 * 60_000L
        const val GLOBAL_LOCK_MS = 15 * 60_000L
    }
}

/**
 * Share to anyone: hands files or folders to a person who does not have Amanah. A QR code holds
 * `http://<address>:<port>/s/<token>/`; scanning it opens a plain page that lists only what was shared.
 *
 * Safety: a random 128-bit token in the address (a wrong one gets the same 404 as any other path), an optional
 * 6-digit PIN with [PinGate], a time limit, [end] cutting downloads in progress, plain HTTP that hangs up on a TLS
 * hello, only IP addresses in the Host header, and a page with no scripts. Nothing is ever logged. Behaves like
 * `anyone.py` in the Windows app.
 *
 * [minutes] 0 means until [end]. After the share ends the address keeps saying "This share has ended" for
 * [LINGER_MS], then closes.
 */
class ShareServer(
    files: List<SendItem>,
    val pin: String?,
    minutes: Int,
    internal val downloads: List<ShareDownload> = emptyList(),
    internal val connectUrl: String = "",
    internal val appVersion: String = "Amanah",
    host: InetAddress? = null,
    firstPort: Int = PORT,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onChange: () -> Unit = {},
) {
    init {
        require(files.isNotEmpty()) { "Nothing to share" }
        require(pin == null || Regex("[0-9]{6}").matches(pin)) { "The PIN must be 6 digits" }
    }

    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    internal val items: List<ShareItem> = group(files)
    private val expiresAt: Long? = if (minutes > 0) clock() + minutes * 60_000L else null
    private val gate = if (pin != null) PinGate(clock) else null
    private val sessions = LinkedHashSet<String>()
    private val visitors = HashSet<String>()
    private var downloadsDone = 0
    private var bytesSent = 0L
    private val lock = Any()
    @Volatile private var stopped = false
    @Volatile private var closed = false
    private val timer = Timer("klshare-share-timer", true)
    private val zips = HashMap<Int, ZipPlan>()
    private val startedAt = System.currentTimeMillis()

    internal val totalSize: Long = items.sumOf { it.size }

    /** What the page lists: files and folders, and the bytes in all of them. For the sharer's screen. */
    val itemCount: Int get() = items.size
    val totalBytes: Long get() = totalSize
    val hasInstaller: Boolean get() = downloads.isNotEmpty()
    internal val zipAll: ZipPlan by lazy { ZipPlan(items.flatMap { zipParts(it) }) }

    private val http = HttpsServer(null, ::handle, host, firstPort, tries = PORT_TRIES, idleTimeoutMs = IDLE_TIMEOUT_MS,
        maxPerIp = MAX_PER_IP, maxTotal = MAX_TOTAL)
    val port: Int get() = http.port

    fun url(host: String): String = "http://${if (':' in host) "[$host]" else host}:$port/s/$token/"

    fun start() {
        http.start()
        expiresAt?.let { at -> timer.schedule(object : TimerTask() { override fun run() = end(cut = false) }, maxOf(0L, at - clock()) + 200) }
    }

    fun live(): Boolean = !stopped && (expiresAt == null || clock() < expiresAt)

    internal fun remainingMs(): Long? = expiresAt?.let { maxOf(0L, it - clock()) }

    fun stats(): ShareStats = synchronized(lock) { ShareStats(live(), visitors.size, downloadsDone, bytesSent, remainingMs()) }

    /** Stops handing anything out. With [cut], downloads already running are dropped too. */
    fun end(cut: Boolean = true) {
        if (closed) return
        val first = !stopped
        stopped = true
        if (cut) http.cutConnections()
        if (first) {
            timer.schedule(object : TimerTask() { override fun run() = close() }, LINGER_MS)
            onChange()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        stopped = true
        timer.cancel()
        http.stop()
        onChange()
    }

    // --- requests ------------------------------------------------------------------------------------------------

    private fun handle(x: Exchange) {
        val head = x.method == "HEAD"
        if (x.method != "GET" && x.method != "HEAD" && x.method != "POST") {
            return x.send(405, "text/plain", "Method not allowed".toByteArray(), SECURITY_HEADERS)
        }
        if (!HttpsServer.hostAllowed(x.headers["host"])) {
            return x.send(421, "text/plain", "Open this by the IP address in the QR code".toByteArray(), SECURITY_HEADERS)
        }
        val m = TOKEN_PATH.matchEntire(x.path)
        if (m == null || !MessageDigest.isEqual(m.groupValues[1].toByteArray(), token.toByteArray())) {
            return page(x, 404, SharePage.notFound(appVersion), head) // the same answer for every wrong address
        }
        val rest = m.groupValues[2]
        if (!live()) return page(x, 410, SharePage.ended(appVersion), head)
        val base = "/s/$token/"
        if (x.method == "POST") return login(x, rest, base)
        if (pin != null && !loggedIn(x)) {
            return if (rest.isEmpty() || rest == "/") page(x, 401, SharePage.login(appVersion), head)
            else x.send(401, "text/plain", "PIN needed".toByteArray(), SECURITY_HEADERS)
        }
        if (rest.isEmpty() || rest == "/") {
            val first = synchronized(lock) { visitors.add(x.remoteIp) }
            if (first) onChange()
            return page(x, 200, SharePage.listing(this, base), head)
        }
        if (rest == "/all.zip") return sendZip(x, zipAll, "Shared files.zip", head)
        val m2 = ROUTE.matchEntire(rest) ?: return page(x, 404, SharePage.notFound(appVersion), head)
        val index = m2.groupValues[2].toIntOrNull() ?: return page(x, 404, SharePage.notFound(appVersion), head)
        when (m2.groupValues[1]) {
            "f" -> items.getOrNull(index)?.takeIf { !it.folder }?.let {
                val f = it.files[0]
                return sendFile(x, f.size, f.name, f.open, head, count = true)
            }
            "z" -> items.getOrNull(index)?.takeIf { it.folder }?.let {
                return sendZip(x, zipFor(index), it.name + ".zip", head)
            }
            "get" -> downloads.getOrNull(index)?.let { return sendFile(x, it.size, it.filename, it.open, head, count = false) }
        }
        page(x, 404, SharePage.notFound(appVersion), head)
    }

    private fun page(x: Exchange, status: Int, body: String, head: Boolean) =
        x.send(status, "text/html; charset=utf-8", body.toByteArray(Charsets.UTF_8), SECURITY_HEADERS, headOnly = head)

    private fun loggedIn(x: Exchange): Boolean {
        val cookie = x.headers["cookie"] ?: return false
        val sid = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("kl_share=") }?.substringAfter('=') ?: return false
        return synchronized(lock) { sid in sessions }
    }

    private fun login(x: Exchange, rest: String, base: String) {
        if ((rest != "/" && rest.isNotEmpty()) || pin == null || gate == null) {
            x.drain()
            return page(x, 404, SharePage.notFound(appVersion), false)
        }
        val length = x.contentLength
        if (length == null || length > LOGIN_BODY_MAX) {
            x.drain()
            return x.send(if (length == null) 411 else 413, "text/plain", "Bad request".toByteArray(), SECURITY_HEADERS)
        }
        val buf = ByteArray(length.toInt())
        var off = 0
        while (off < buf.size) {
            val n = x.body.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        x.bodyRead = true
        val typed = HttpsServer.parseQuery(String(buf, 0, off, Charsets.UTF_8))["pin"]?.trim().orEmpty()
        when (gate.check(x.remoteIp, typed, pin)) {
            PinGate.Result.OK -> {
                val sid = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                synchronized(lock) {
                    if (sessions.size >= SESSIONS_MAX) sessions.remove(sessions.first())
                    sessions += sid
                }
                x.send(303, "text/plain", ByteArray(0),
                    SECURITY_HEADERS + mapOf("Set-Cookie" to "kl_share=$sid; Path=$base; HttpOnly; SameSite=Strict", "Location" to base))
            }
            PinGate.Result.LOCKED -> {
                val seconds = maxOf(1L, gate.waitMs(x.remoteIp) / 1000 + 1)
                val minutes = maxOf(1L, Math.round(seconds / 60.0))
                x.send(429, "text/html; charset=utf-8",
                    SharePage.login(appVersion, "Too many wrong PINs. Try again in $minutes minute(s).").toByteArray(Charsets.UTF_8),
                    SECURITY_HEADERS + mapOf("Retry-After" to seconds.toString()))
            }
            PinGate.Result.WRONG -> page(x, 401, SharePage.login(appVersion, "That PIN is not right."), false)
        }
    }

    private fun zipParts(item: ShareItem): List<ZipPart> =
        item.files.map { f -> ZipPart(f.name, f.size, startedAt, f.open) }

    private fun zipFor(index: Int): ZipPlan = synchronized(lock) { zips.getOrPut(index) { ZipPlan(zipParts(items[index])) } }

    private fun attachment(name: String): String =
        "attachment; filename*=UTF-8''" + URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("*", "%2A")

    private fun sendZip(x: Exchange, plan: ZipPlan, name: String, head: Boolean) {
        var sent = 0L
        try {
            x.sendStream(200, "application/zip", plan.length,
                SECURITY_HEADERS + mapOf("Content-Disposition" to attachment(name), "Accept-Ranges" to "none"), head) { out ->
                plan.writeTo(Counting(out) { sent += it })
            }
        } finally {
            if (!head) noteDone(sent, sent == plan.length)
        }
    }

    private fun sendFile(x: Exchange, size: Long, name: String, open: () -> InputStream, head: Boolean, count: Boolean) {
        val range = if (size > 0) parseRange(x.headers["range"], size) else RangeSpec.Whole
        if (range is RangeSpec.Bad) {
            return x.send(416, "text/plain", ByteArray(0), SECURITY_HEADERS + mapOf("Content-Range" to "bytes */$size"))
        }
        val (start, end) = if (range is RangeSpec.Part) range.start to range.end else 0L to size - 1
        val length = if (size > 0) end - start + 1 else 0L
        val extra = SECURITY_HEADERS + buildMap {
            put("Content-Disposition", attachment(name))
            put("Accept-Ranges", "bytes")
            if (range is RangeSpec.Part) put("Content-Range", "bytes $start-$end/$size")
        }
        var sent = 0L
        try {
            x.sendStream(if (range is RangeSpec.Part) 206 else 200, "application/octet-stream", length, extra, head) { out ->
                if (length > 0) open().use { input ->
                    input.skipNBytesCompat(start)
                    val buf = ByteArray(64 * 1024)
                    var left = length
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("$name is shorter than it was when it was shared")
                        out.write(buf, 0, n)
                        left -= n
                        sent += n
                    }
                }
            }
        } finally {
            if (!head && count) noteDone(sent, sent == size)
        }
    }

    private fun noteDone(sent: Long, whole: Boolean) {
        synchronized(lock) {
            bytesSent += sent
            if (whole) downloadsDone++
        }
        onChange()
    }

    private class Counting(private val out: OutputStream, private val add: (Int) -> Unit) : OutputStream() {
        override fun write(b: Int) { out.write(b); add(1) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); add(len) }
        override fun flush() = out.flush()
    }

    internal sealed interface RangeSpec {
        object Whole : RangeSpec
        object Bad : RangeSpec
        class Part(val start: Long, val end: Long) : RangeSpec
    }

    companion object {
        const val PORT = 47890 // the next ones are tried when it is taken
        const val PORT_TRIES = 10
        const val IDLE_TIMEOUT_MS = 120_000
        const val MAX_PER_IP = 16
        const val MAX_TOTAL = 64
        const val LINGER_MS = 5 * 60_000L
        private const val LOGIN_BODY_MAX = 1024L
        private const val SESSIONS_MAX = 256

        /** The time limits the screens offer, in minutes; 0 is "until I stop it". */
        val TIME_LIMITS = listOf("15 minutes" to 15, "1 hour" to 60, "4 hours" to 240, "Until I stop it" to 0)
        const val DEFAULT_MINUTES = 60

        private val TOKEN_PATH = Regex("/s/([A-Za-z0-9_-]{16,64})(/.*)?")
        private val ROUTE = Regex("/(f|z|get)/(\\d{1,4})")
        private val SECURITY_HEADERS = mapOf(
            "Content-Security-Policy" to SharePage.CSP,
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
        )
        private val RANGE = Regex("\\s*bytes\\s*=\\s*(\\d*)\\s*-\\s*(\\d*)\\s*")

        /** A single "bytes=" range; null-like [RangeSpec.Whole] for none or nonsense, [RangeSpec.Bad] if it can't be met. */
        internal fun parseRange(header: String?, size: Long): RangeSpec {
            val m = RANGE.matchEntire(header ?: return RangeSpec.Whole) ?: return RangeSpec.Whole
            val (a, b) = m.destructured
            if (a.isEmpty() && b.isEmpty()) return RangeSpec.Whole
            val start: Long
            val end: Long
            if (a.isNotEmpty()) {
                start = a.toLongOrNull() ?: return RangeSpec.Bad
                end = if (b.isNotEmpty()) minOf(b.toLongOrNull() ?: Long.MAX_VALUE, size - 1) else size - 1
            } else {
                val n = b.toLongOrNull() ?: return RangeSpec.Bad
                if (n == 0L) return RangeSpec.Bad
                start = maxOf(0L, size - n)
                end = size - 1
            }
            return if (start >= size || start > end) RangeSpec.Bad else RangeSpec.Part(start, end)
        }

        /**
         * Groups what was picked into the things the page lists. A name with a "/" in it belongs to the folder before
         * the slash. A single file that would clash with a folder's name gets a number.
         */
        internal fun group(files: List<SendItem>): List<ShareItem> {
            val folders = files.filter { '/' in it.name }.map { it.name.substringBefore('/').lowercase() }.toSet()
            val taken = HashSet<String>(folders)
            val order = LinkedHashMap<String, MutableList<SendItem>>()
            val isFolder = HashSet<String>()
            for (f in files) {
                if ('/' in f.name) {
                    val top = f.name.substringBefore('/')
                    order.getOrPut("d:" + top.lowercase()) { ArrayList() } += f
                    isFolder += "d:" + top.lowercase()
                } else {
                    var name = f.name
                    var n = 2
                    while (!taken.add(name.lowercase())) name = numbered(f.name, n++)
                    order["f:" + name.lowercase()] = arrayListOf(SendItem(name, f.size, f.open))
                }
            }
            return order.map { (key, list) ->
                val folder = key in isFolder
                ShareItem(folder, if (folder) list[0].name.substringBefore('/') else list[0].name, list)
            }
        }

        private fun numbered(name: String, n: Int): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
        }
    }
}
