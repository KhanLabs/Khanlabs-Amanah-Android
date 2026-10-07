package dev.khanlabs.lanshare.net

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** A copy of Amanah this device can hand over (its own APK): [label] on the button, [filename] when saved. */
class ShareDownload(val label: String, val filename: String, val size: Long, val open: () -> InputStream)

class ShareStats(
    val live: Boolean,
    val visitors: Int,
    val downloads: Int,
    val bytes: Long,
    val remainingMs: Long?,
    /** All files, and how many of them are ready to download, still being checked, or could not be read. */
    val files: Int = 0,
    val ready: Int = 0,
    val preparing: Int = 0,
    val failed: Int = 0,
    /** Files where a download was cut because the file no longer matched its checksum. */
    val problems: Int = 0,
)

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
 * What is shared can grow while the share is live ([addFiles]). Every file gets an id that is never reused, so a link
 * always means the same file. A file is offered only after it was read once and found good (its real size, CRC-32 and
 * SHA-256 are then known); every download is checked against that, and a file that changed ends the download short
 * instead of arriving whole but wrong. ZIPs are made from frozen lists of files, in parts under 2 GiB, with the real
 * size and checksum of each file in its header, so any unzip app opens them and a broken download can be resumed.
 *
 * Safety: a random 128-bit token in the address (a wrong one gets the same 404 as any other path), an optional
 * 6-digit PIN with [PinGate], a time limit, [end] cutting downloads in progress, plain HTTP that hangs up on a TLS
 * hello, only IP addresses in the Host header, and one tiny inline script allowed by a hash. Nothing is ever logged.
 *
 * [minutes] 0 means until [end]. After the share ends the address keeps saying "This share has ended" for
 * [LINGER_MS], then closes. With a [stash] folder, files of unknown size (and, with [stashAll], every file) are also
 * copied there while they are checked, so downloads don't depend on the original staying where it was.
 */
class ShareServer(
    files: List<SendItem>,
    val pin: String?,
    minutes: Int,
    internal val downloads: List<ShareDownload> = emptyList(),
    connectUrl: String = "",
    internal val appVersion: String = "Amanah",
    host: InetAddress? = null,
    firstPort: Int = PORT,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onChange: () -> Unit = {},
    stash: File? = null,
    private val stashAll: Boolean = false,
    /** Limits of one ZIP; tests lower them. */
    private val zipPartMax: Long = ZIP_PART_MAX,
    private val zipPartFiles: Int = ZIP_PART_FILES,
) {
    init {
        require(files.isNotEmpty()) { "Nothing to share" }
        require(files.size <= MAX_FILES) { "A share can hold up to $MAX_FILES files" }
        require(pin == null || Regex("[0-9]{6}").matches(pin)) { "The PIN must be 6 digits" }
    }

    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** The "open in Amanah" link without the Wi-Fi name and password: anyone with the address can read this page. */
    internal val connectLink: String = connectUrl.replace(Regex("&(ssid|psk)=[^&]*"), "")

    private val stashDir: File? = stash?.let { File(it, token.take(12)) }
    private val expiresAt: Long? = if (minutes > 0) clock() + minutes * 60_000L else null
    private val gate = if (pin != null) PinGate(clock) else null
    private val sessions = LinkedHashSet<String>()
    private val lock = Any()
    private var visitorCount = 0
    private var downloadsDone = 0
    private var bytesSent = 0L
    @Volatile private var stopped = false
    @Volatile private var closed = false
    private val timer = Timer("klshare-share-timer", true)

    // what is shared: a copy-on-write list, so a page or a ZIP in the making never sees it change under it
    private val list = AtomicReference<List<ShareFile>>(emptyList())
    private val addLock = Any()
    private val book = NameBook()
    private var nextId = 0
    @Volatile private var batchNo = 0
    private val prepare: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "klshare-prepare").apply { isDaemon = true }
    }

    private class Visitor(val baseline: Int) {
        var viewed = false
        val got = HashSet<Int>()
    }

    private val seen = object : LinkedHashMap<String, Visitor>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Visitor>) = size > MAX_VISITORS
    }

    private class Active(val x: Exchange) {
        @Volatile var last = System.nanoTime() / 1_000_000
    }

    private val active = ConcurrentHashMap.newKeySet<Active>()

    init {
        add(files)
    }

    /** What the page lists: files and folders, and the bytes in all of them. For the sharer's screen. */
    val itemCount: Int get() = ShareItem.group(list.get()).size
    val totalBytes: Long get() = list.get().sumOf { it.size }
    val hasInstaller: Boolean get() = downloads.isNotEmpty()

    /** Every file, in the order they were added, for the sharer's screen. */
    val sharedFiles: List<ShareFile> get() = list.get()

    private val http = HttpsServer(null, ::handle, host, firstPort, tries = PORT_TRIES, idleTimeoutMs = IDLE_TIMEOUT_MS,
        maxPerIp = MAX_PER_IP, maxTotal = MAX_TOTAL)
    val port: Int get() = http.port

    fun url(host: String): String = "http://${if (':' in host) "[$host]" else host}:$port/s/$token/"

    fun start() {
        http.start()
        expiresAt?.let { at -> timer.schedule(object : TimerTask() { override fun run() = end(cut = false) }, maxOf(0L, at - clock()) + 200) }
        timer.schedule(object : TimerTask() { override fun run() = cutStalled() }, WATCH_MS, WATCH_MS)
    }

    fun live(): Boolean = !stopped && (expiresAt == null || clock() < expiresAt)

    internal fun remainingMs(): Long? = expiresAt?.let { maxOf(0L, it - clock()) }

    fun stats(): ShareStats {
        val fs = list.get()
        var ready = 0
        var preparing = 0
        var failed = 0
        var problems = 0
        for (f in fs) {
            when (f.state) {
                ShareFile.State.READY -> ready++
                ShareFile.State.PREPARING -> preparing++
                ShareFile.State.FAILED -> failed++
            }
            if (f.problem.isNotEmpty()) problems++
        }
        return synchronized(lock) {
            ShareStats(live(), visitorCount, downloadsDone, bytesSent, remainingMs(), fs.size, ready, preparing, failed, problems)
        }
    }

    /**
     * Adds more files to a live share. Nothing already shared changes: the new files get new ids, a name that clashes
     * gets a number, and the page shows them at the top as just added. Returns how many were added.
     */
    fun addFiles(items: List<SendItem>): Int {
        check(live()) { "This share has ended" }
        if (items.isEmpty()) return 0
        val n = add(items)
        onChange()
        return n
    }

    private fun add(items: List<SendItem>): Int {
        val added = synchronized(addLock) {
            val current = list.get()
            require(current.size + items.size <= MAX_FILES) { "A share can hold up to $MAX_FILES files" }
            batchNo++
            val now = System.currentTimeMillis()
            val names = book.assign(items.map { it.name })
            val fresh = items.mapIndexed { i, it -> ShareFile(nextId++, batchNo, now, names[i], it) }
            list.set(current + fresh)
            fresh
        }
        // small files first, so the page is usable early; they are listed in the order they were added
        for (f in added.sortedBy { it.source.size }) {
            try {
                prepare.execute {
                    if (!stopped) f.prepare(stashDir, stashAll) { stopped }
                    onChange()
                }
            } catch (e: Exception) {
                // the share was closed while this was being added
            }
        }
        return added.size
    }

    /** Stops handing anything out. With [cut], downloads already running are dropped too. */
    fun end(cut: Boolean = true) {
        if (closed) return
        val first = !stopped
        stopped = true
        if (cut) http.cutConnections()
        if (first) {
            prepare.shutdownNow()
            dropCopies()
            timer.schedule(object : TimerTask() { override fun run() = close() }, LINGER_MS)
            onChange()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        stopped = true
        timer.cancel()
        prepare.shutdownNow()
        http.stop()
        dropCopies()
        onChange()
    }

    private fun dropCopies() {
        for (f in list.get()) f.discardCopy()
        stashDir?.deleteRecursively()
    }

    private fun cutStalled() {
        val now = System.nanoTime() / 1_000_000
        for (a in active) if (now - a.last > STALL_MS) a.x.cut()
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
        if (rest.isEmpty() || rest == "/") return listing(x, base, head)
        when (rest) {
            "/all.zip" -> return allZip(x, base)
            "/v" -> return text(x, 200, version(), head)
            "/checksums.sha256" -> return checksums(x, head)
        }
        ZIP_ROUTE.matchEntire(rest)?.let { z ->
            val snap = synchronized(snapLock) { snapshots[z.groupValues[1].toInt()] }
            val group = snap?.parts?.getOrNull(z.groupValues[2].toInt())
            if (snap == null || group == null) return page(x, 404, SharePage.notFound(appVersion), head)
            val name = if (snap.parts.size > 1) "${snap.name} (part ${z.groupValues[2].toInt() + 1} of ${snap.parts.size}).zip" else "${snap.name}.zip"
            val plan = group.plan
            return serve(x, Entity(plan.length, "application/zip", name, plan.etag, true, group.files.map { it.id }) { out, s, e ->
                plan.writeTo(out, s, e)
            }, head)
        }
        val m2 = ROUTE.matchEntire(rest) ?: return page(x, 404, SharePage.notFound(appVersion), head)
        val index = m2.groupValues[2].toIntOrNull() ?: return page(x, 404, SharePage.notFound(appVersion), head)
        when (m2.groupValues[1]) {
            "f" -> list.get().firstOrNull { it.id == index }?.let { return file(x, it, head) }
            "get" -> downloads.getOrNull(index)?.let { d ->
                return serve(x, Entity(d.size, "application/octet-stream", d.filename, null, false, emptyList()) { out, s, e ->
                    copyChecked(d.filename, d.open, d.size, null, s, e - s + 1, out)
                }, head)
            }
        }
        page(x, 404, SharePage.notFound(appVersion), head)
    }

    private fun page(x: Exchange, status: Int, body: String, head: Boolean) =
        x.send(status, "text/html; charset=utf-8", body.toByteArray(Charsets.UTF_8), SECURITY_HEADERS, headOnly = head)

    private fun text(x: Exchange, status: Int, body: String, head: Boolean, extra: Map<String, String> = emptyMap()) =
        x.send(status, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8), SECURITY_HEADERS + extra, headOnly = head)

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

    // --- the page ------------------------------------------------------------------------------------------------

    /** Changes whenever the list changes (a file added, a file ready or failed), so the page can tell its reader. */
    private fun version(): String {
        val fs = list.get()
        return "${fs.size}.${fs.count { it.state == ShareFile.State.PREPARING }}.${fs.count { it.state == ShareFile.State.FAILED }}"
    }

    private fun visitor(ip: String): Visitor = seen.getOrPut(ip) { Visitor(batchNo) }

    private fun listing(x: Exchange, base: String, head: Boolean) {
        var first = false
        val (baseline, got) = synchronized(lock) {
            val v = visitor(x.remoteIp)
            if (!v.viewed) {
                v.viewed = true
                visitorCount++
                first = true
            }
            v.baseline to HashSet(v.got)
        }
        if (first) onChange()
        val fs = list.get()
        val fresh = fs.filter { it.batch > baseline && it.id !in got }
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        val earlier = fs.filter { it.id !in freshIds }
        val freshItems = ShareItem.group(fresh)
        val earlierItems = ShareItem.group(earlier)
        val folderZips = HashMap<Int, ZipOffer>()
        for (item in freshItems + earlierItems) if (item.folder) offer(item.files, item.name, base)?.let { folderZips[item.firstId] = it }
        val model = ListingModel(
            base = base, appVersion = appVersion, remainingMs = remainingMs(),
            totalFiles = fs.size, totalBytes = fs.sumOf { it.size },
            fresh = freshItems, earlier = earlierItems, got = got,
            everything = offer(fs, "Shared files", base),
            freshZip = if (fresh.isNotEmpty()) offer(fresh, "New files", base) else null,
            folderZips = folderZips, version = version(),
            preparing = fs.count { it.state == ShareFile.State.PREPARING },
            failed = fs.filter { it.state == ShareFile.State.FAILED },
            downloads = downloads, connectUrl = connectLink,
        )
        page(x, 200, SharePage.listing(model), head)
    }

    private fun checksums(x: Exchange, head: Boolean) {
        val body = list.get().filter { it.state == ShareFile.State.READY }
            .joinToString("") { "${it.sha256}  ${it.name.replace(Regex("[\\r\\n]"), "_")}\n" }
        x.send(200, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8),
            SECURITY_HEADERS + mapOf("Content-Disposition" to attachment("checksums.sha256")), headOnly = head)
    }

    // --- ZIP snapshots -------------------------------------------------------------------------------------------

    /** A group of files that go into one ZIP; the plan is built when first needed. */
    private class ZipGroup(val files: List<ShareFile>) {
        val plan: ZipPlan by lazy {
            ZipPlan(files.map { f -> ZipPart(f.name, f.size, f.crc, f.modified) { f.open() } })
        }
    }

    /** A frozen list of ready files: a ZIP made from it is the same bytes every time, so a download can be resumed. */
    private class Snapshot(val id: Int, val name: String, val parts: List<ZipGroup>, val loose: List<ShareFile>, val weight: Int)

    private val snapLock = Any()
    private val snapshots = LinkedHashMap<Int, Snapshot>(16, 0.75f, true)
    private val snapshotKeys = HashMap<String, Int>()
    private var snapshotWeight = 0
    private var nextSnapshot = 1

    private fun snapshot(files: List<ShareFile>, name: String): Snapshot = synchronized(snapLock) {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(name.toByteArray(Charsets.UTF_8))
        for (f in files) md.update(byteArrayOf(0, (f.id ushr 24).toByte(), (f.id ushr 16).toByte(), (f.id ushr 8).toByte(), f.id.toByte()))
        val key = md.digest().joinToString("") { "%02x".format(it) }
        snapshotKeys[key]?.let { id -> snapshots[id]?.let { return it } }
        val (parts, loose) = pack(files)
        val snap = Snapshot(nextSnapshot++, name, parts, loose, files.size)
        snapshots[snap.id] = snap
        snapshotKeys[key] = snap.id
        snapshotWeight += snap.weight
        // keep the newest, within a budget, so many visitors can't make the server hold every list ever shown
        val oldest = snapshots.entries.iterator()
        while (oldest.hasNext() && (snapshots.size > MAX_SNAPSHOTS || snapshotWeight > MAX_SNAPSHOT_FILES) && snapshots.size > 1) {
            val old = oldest.next().value
            if (old === snap) break
            oldest.remove()
            snapshotWeight -= old.weight
            snapshotKeys.values.remove(old.id)
        }
        snap
    }

    /** Splits files into ZIPs that stay under 2 GiB; a file that big is not zipped, it is downloaded on its own. */
    private fun pack(files: List<ShareFile>): Pair<List<ZipGroup>, List<ShareFile>> {
        val groups = ArrayList<ZipGroup>()
        val loose = ArrayList<ShareFile>()
        var current = ArrayList<ShareFile>()
        var bytes = 0L
        for (f in files) {
            if (f.size >= zipPartMax) {
                loose += f
                continue
            }
            val cost = f.size + 200 + 2L * f.name.toByteArray(Charsets.UTF_8).size // data plus both headers
            if (current.isNotEmpty() && (bytes + cost > zipPartMax || current.size >= zipPartFiles)) {
                groups += ZipGroup(current)
                current = ArrayList()
                bytes = 0
            }
            current += f
            bytes += cost
        }
        if (current.isNotEmpty()) groups += ZipGroup(current)
        return groups to loose
    }

    /** The ZIP buttons for these files, or null when a ZIP would not help (fewer than two ready files). */
    private fun offer(files: List<ShareFile>, name: String, base: String): ZipOffer? {
        val ready = files.filter { it.state == ShareFile.State.READY }
        if (ready.size < 2) return null
        val snap = snapshot(ready, name)
        if (snap.parts.isEmpty()) return null
        val links = snap.parts.mapIndexed { i, g ->
            ZipLink("${base}zip/${snap.id}-$i", if (snap.parts.size > 1) "Part ${i + 1} of ${snap.parts.size}" else "", g.plan.length, g.files.size)
        }
        return ZipOffer(links, snap.loose)
    }

    private fun allZip(x: Exchange, base: String) {
        val fs = list.get()
        val ready = fs.filter { it.state == ShareFile.State.READY }
        val snap = if (ready.isEmpty()) null else snapshot(ready, "Shared files")
        if (snap == null || snap.parts.isEmpty()) {
            return if (fs.any { it.state == ShareFile.State.PREPARING }) {
                text(x, 503, "Still getting the files ready. Try again in a few seconds.", false, mapOf("Retry-After" to "3"))
            } else page(x, 404, SharePage.notFound(appVersion), false)
        }
        x.send(303, "text/plain", ByteArray(0), SECURITY_HEADERS + mapOf("Location" to "${base}zip/${snap.id}-0"))
    }

    // --- downloads -----------------------------------------------------------------------------------------------

    private class Entity(
        val length: Long,
        val type: String,
        val name: String?,
        /** A strong validator, or null (then ranges are served but a changed file can't be told from the first). */
        val etag: String?,
        /** Whether finishing it counts as a download on the sharer's screen. */
        val counts: Boolean,
        /** The files this holds, marked as delivered to the visitor when all of it has gone out. */
        val delivered: List<Int>,
        val write: (OutputStream, Long, Long) -> Unit,
    )

    private fun file(x: Exchange, f: ShareFile, head: Boolean) {
        when (f.state) {
            ShareFile.State.PREPARING ->
                return text(x, 503, "Still getting this file ready. Try again in a few seconds.", head, mapOf("Retry-After" to "3"))
            ShareFile.State.FAILED ->
                return text(x, 410, "This file could not be read on the sharing device.", head)
            ShareFile.State.READY -> {}
        }
        serve(x, Entity(f.size, "application/octet-stream", f.name, f.etag, true, listOf(f.id)) { out, s, e ->
            copyChecked(f.name, { f.open() }, f.size, f.crc, s, e - s + 1, out)
        }, head)
    }

    private fun attachment(name: String): String =
        "attachment; filename*=UTF-8''" + URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("*", "%2A")

    /** One body, whole or a single range of it, with a validator so a browser can resume after a break. */
    private fun serve(x: Exchange, e: Entity, head: Boolean) {
        val ifRange = x.headers["if-range"]
        val range = if (e.length > 0 && (ifRange == null || ifRange == e.etag)) parseRange(x.headers["range"], e.length) else RangeSpec.Whole
        if (range is RangeSpec.Bad) {
            return x.send(416, "text/plain", ByteArray(0), SECURITY_HEADERS + mapOf("Content-Range" to "bytes */${e.length}"))
        }
        val (start, end) = if (range is RangeSpec.Part) range.start to range.end else 0L to e.length - 1
        val length = if (e.length > 0) end - start + 1 else 0L
        val extra = SECURITY_HEADERS + buildMap {
            if (e.name != null) put("Content-Disposition", attachment(e.name))
            if (e.etag != null) put("ETag", e.etag)
            put("Accept-Ranges", "bytes")
            if (range is RangeSpec.Part) put("Content-Range", "bytes $start-$end/${e.length}")
        }
        val watch = Active(x)
        var sent = 0L
        active += watch
        try {
            x.sendStream(if (range is RangeSpec.Part) 206 else 200, e.type, length, extra, head) { out ->
                if (length > 0) e.write(Counting(out) { sent += it; watch.last = System.nanoTime() / 1_000_000 }, start, end)
            }
        } catch (c: ChangedException) {
            noteProblem(c)
            throw c
        } finally {
            active -= watch
            if (!head) finish(x.remoteIp, e, start, end, sent)
        }
    }

    private fun noteProblem(c: ChangedException) {
        list.get().firstOrNull { it.name == c.name }?.problem = c.message ?: "changed"
        onChange()
    }

    private fun finish(ip: String, e: Entity, start: Long, end: Long, sent: Long) {
        val wanted = if (e.length > 0) end - start + 1 else 0L
        val complete = sent == wanted
        val toEnd = e.length == 0L || end == e.length - 1
        synchronized(lock) {
            bytesSent += sent
            if (e.counts && complete && toEnd && start == 0L) downloadsDone++
            // a resumed tail counts too, but a peek at the end of a ZIP does not
            if (complete && toEnd && e.delivered.isNotEmpty() && (start == 0L || sent * 2 >= e.length)) visitor(ip).got.addAll(e.delivered)
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

        /** A share holds at most this many files; the page and the ZIPs stay quick. */
        const val MAX_FILES = 5_000

        /** A ZIP part stays under 2 GiB (many unzip apps break past it) and under this many files. */
        const val ZIP_PART_MAX = 1_900_000_000L
        const val ZIP_PART_FILES = 10_000

        private const val LOGIN_BODY_MAX = 1024L
        private const val SESSIONS_MAX = 256
        private const val MAX_VISITORS = 512
        private const val MAX_SNAPSHOTS = 64
        private const val MAX_SNAPSHOT_FILES = 20_000

        /** A download that has taken no byte for this long is cut; the browser can resume it. */
        private const val STALL_MS = 90_000L
        private const val WATCH_MS = 15_000L

        /** The time limits the screens offer, in minutes; 0 is "until I stop it". */
        val TIME_LIMITS = listOf("15 minutes" to 15, "1 hour" to 60, "4 hours" to 240, "Until I stop it" to 0)
        const val DEFAULT_MINUTES = 60

        private val TOKEN_PATH = Regex("/s/([A-Za-z0-9_-]{16,64})(/.*)?")
        private val ROUTE = Regex("/(f|get)/(\\d{1,6})")
        private val ZIP_ROUTE = Regex("/zip/(\\d{1,6})-(\\d{1,3})")
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
    }
}
