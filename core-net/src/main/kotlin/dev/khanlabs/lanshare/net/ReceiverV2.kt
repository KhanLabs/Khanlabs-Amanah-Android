package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.BatchReply
import dev.khanlabs.lanshare.protocol.BatchResult
import dev.khanlabs.lanshare.protocol.ChunkReply
import dev.khanlabs.lanshare.protocol.ChunkSet
import dev.khanlabs.lanshare.protocol.DoneReply
import dev.khanlabs.lanshare.protocol.DoneRequest
import dev.khanlabs.lanshare.protocol.FileEntry
import dev.khanlabs.lanshare.protocol.FileState
import dev.khanlabs.lanshare.protocol.InfoV2
import dev.khanlabs.lanshare.protocol.ManifestPage
import dev.khanlabs.lanshare.protocol.ManifestReply
import dev.khanlabs.lanshare.protocol.Names
import dev.khanlabs.lanshare.protocol.OkReply
import dev.khanlabs.lanshare.protocol.ProtocolException
import dev.khanlabs.lanshare.protocol.ProtocolJson
import dev.khanlabs.lanshare.protocol.Sender
import dev.khanlabs.lanshare.protocol.SenderV2
import dev.khanlabs.lanshare.protocol.SessionCreate
import dev.khanlabs.lanshare.protocol.SessionState
import dev.khanlabs.lanshare.protocol.Summary
import dev.khanlabs.lanshare.protocol.V1
import dev.khanlabs.lanshare.protocol.V2
import dev.khanlabs.lanshare.protocol.V2Limits
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What the receiver keeps for one v2 session next to its [Offer]. */
internal class SessionV2(
    val offer: Offer,
    val list: CopyOnWriteArrayList<IncomingFile>,
    val senderId: String,
    val chunkSize: Long,
    val summary: Summary,
    val token: String,
    val dir: File,
) {
    val monitor = Object()
    @Volatile var version = 1L
        private set
    @Volatile var manifestComplete = false
    /** The file IDs the person chose, or null for all. */
    @Volatile var wanted: Set<String>? = null
    val files = HashMap<String, IncomingFile>()
    val ids = HashSet<String>()
    /** Empty folders to make when the session is done, as safe relative names. */
    val dirs = ArrayList<String>()
    @Volatile var entries = 0L
    @Volatile var bytes = 0L
    @Volatile var lastProgress = 0L
    val journal = Journal(File(dir, JOURNAL))

    /** Something a long-poll cares about changed: wakes the waiting requests. */
    fun bump() {
        synchronized(monitor) {
            version++
            monitor.notifyAll()
        }
    }

    fun wantedFile(f: IncomingFile): Boolean = wanted?.contains(f.id) ?: true

    companion object {
        const val JOURNAL = "journal.log"
    }
}

/**
 * The protocol v2 API of a receiver (PROTOCOL-v2.md): sessions, the list of files in pages, chunks checked with SHA-256,
 * batches of small files, resume after a restart, cancel and drop. A session is shown to the app as an [Offer], so the
 * prompt, the cards, the notifications and the history work as they do for v1.
 *
 * Parts of unfinished files and the journal of each session live under [partsDir], in app-private storage. A file is
 * copied into the receive store once it is whole and its digest agrees. The device proof is checked here; whether a proven
 * sender is trusted is decided by the app through [Receiver] (not built yet).
 */
internal class ReceiverV2(
    private val rx: Receiver,
    private val identity: Identity,
    private val partsDir: File,
    private val limits: V2Limits = DEFAULT_LIMITS,
    private val maxEntries: Int = MAX_ENTRIES,
    /** How long a session waits for the person's answer. */
    private val pendingMs: Long = 10 * 60_000L,
) {
    val nonces = NonceStore()
    private val tokenKey: ByteArray = MessageDigest.getInstance("SHA-256")
        .digest("KLv2-token".toByteArray() + identity.privateKey.encoded)

    /**
     * The token of a session is made from a secret of this device, so it can be given again when the sender asks to resume,
     * also after this app restarted, and no token is ever kept on disk (PROTOCOL-v2.md section 16).
     */
    private fun tokenFor(sessionId: String, senderId: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(tokenKey, "HmacSHA256")) }
        return V2.b64url(mac.doFinal("$sessionId\n$senderId".toByteArray()).copyOf(16))
    }

    // --- routing

    fun handle(x: Exchange) {
        val parts = x.path.removePrefix(V2.API).split('/').filter { it.isNotEmpty() }
        try {
            when {
                x.method == "GET" && parts == listOf("info") -> x.sendJson(200, InfoV2.serializer(), info(x.remoteIp))
                x.method == "POST" && parts == listOf("sessions") -> createSession(x)
                parts.size >= 2 && parts[0] == "sessions" -> sessionRoute(x, parts)
                else -> x.fail(404, "not_found", "Unknown endpoint")
            }
        } catch (e: ProtocolException) {
            x.fail(e.status, e.code, e.message ?: e.code)
        } catch (e: IOException) {
            throw e // the connection is gone; nothing to answer
        } catch (e: Exception) {
            // a bug here must not drop the connection and leave the sender guessing
            if (!x.answered) x.fail(500, "failed", "The receiver hit a problem: ${e.javaClass.simpleName}")
        }
    }

    fun info(ip: String): InfoV2 {
        val s = rx.currentSettings()
        return InfoV2(id = identity.id, name = s.name, model = rx.model, app = rx.app, receiving = s.receiving,
            features = listOf("proof", "resume", "batch"), limits = limits, nonce = nonces.issue(ip))
    }

    private fun sessionRoute(x: Exchange, parts: List<String>) {
        val sid = parts[1]
        if (!V2.TOKEN.matches(sid)) throw ProtocolException(400, "bad_session", "Bad session ID")
        val s = rx.get(sid)?.v2 ?: throw ProtocolException(404, "no_session", "Unknown or forgotten session")
        authorize(x, s)
        s.offer.lastSeen = System.nanoTime()
        expireIfDue(s)
        val files = parts.getOrNull(2) == "files"
        when {
            parts.size == 2 && x.method == "GET" -> poll(x, s)
            parts.size == 2 && x.method == "DELETE" -> cancel(x, s)
            parts.size == 3 && parts[2] == "manifest" && x.method == "POST" -> manifest(x, s)
            parts.size == 3 && parts[2] == "batch" && x.method == "POST" -> batch(x, s)
            files && parts.size == 4 && x.method == "DELETE" -> dropFile(x, s, parts[3])
            files && parts.size == 5 && parts[4] == "chunks" && x.method == "DELETE" -> restartFile(x, s, parts[3])
            files && parts.size == 5 && parts[4] == "done" && x.method == "POST" -> doneFile(x, s, parts[3])
            files && parts.size == 6 && parts[4] == "chunks" && x.method == "PUT" -> putChunk(x, s, parts[3], parts[5])
            else -> x.fail(404, "not_found", "Unknown endpoint")
        }
    }

    private fun authorize(x: Exchange, s: SessionV2) {
        val header = x.headers["authorization"].orEmpty()
        val given = if (header.startsWith("Bearer ", ignoreCase = true)) header.substring(7).trim() else ""
        if (!MessageDigest.isEqual(given.toByteArray(), s.token.toByteArray())) {
            throw ProtocolException(403, "bad_token", "Missing or wrong session token")
        }
    }

    private fun <T> readJson(x: Exchange, serializer: KSerializer<T>): T {
        val length = x.contentLength ?: throw ProtocolException(411, "length_required", "Content-Length is required")
        if (length > MAX_JSON) throw ProtocolException(413, "too_large", "Request too large")
        x.bodyRead = true
        val body = x.body.readBytes()
        return try {
            ProtocolJson.decodeFromString(serializer, body.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ProtocolException(400, "bad_json", "The body is not the JSON this call needs")
        }
    }

    // --- creating a session

    private fun createSession(x: Exchange) {
        val req = readJson(x, SessionCreate.serializer())
        if (!V2.TOKEN.matches(req.session)) throw ProtocolException(400, "bad_session", "session must be 22 to 64 letters, digits, - or _")
        if (!rx.currentSettings().receiving) throw ProtocolException(403, "not_receiving", "This device isn't accepting files right now")
        val proven = DeviceProof.check(nonces, x.remoteIp, identity.id, "session", req.session, req.sender, req.cert, req.nonce, req.proof)

        rx.get(req.session)?.let { existing ->
            // the same session again: a sender that is resuming (section 6.1)
            val s = existing.v2
            if (s == null || s.senderId != proven.id) throw ProtocolException(409, "duplicate", "That session ID belongs to another sender")
            if (req.summary.count != s.summary.count || req.summary.bytes != s.summary.bytes) {
                throw ProtocolException(409, "changed", "That session ID was used for another list")
            }
            expireIfDue(s)
            s.offer.lastSeen = System.nanoTime()
            return sendState(x, s, withToken = true)
        }

        val text = req.text.orEmpty()
        if (text.toByteArray(Charsets.UTF_8).size > V1.MAX_TEXT) throw ProtocolException(400, "bad_text", "text must be at most 64 KB")
        val summary = req.summary
        if (summary.count < 0 || summary.bytes < 0) throw ProtocolException(400, "bad_files", "summary needs a count and a size")
        if (summary.count > maxEntries) throw ProtocolException(413, "too_many", "More entries than this device takes in one send")
        if (req.files.size > limits.manifestPage) throw ProtocolException(400, "bad_files", "A page has at most ${limits.manifestPage} entries")
        if (summary.count == 0L && text.isEmpty()) throw ProtocolException(400, "empty", "Send at least one file or some text")

        val list = CopyOnWriteArrayList<IncomingFile>()
        val sender = Sender(proven.id, req.sender.name.take(128), req.sender.model.take(128), req.sender.app.take(128))
        val offer = Offer(req.session, sender, x.remoteIp, list, text, "", skipOk = true)
        val dir = File(partsDir, req.session)
        val s = SessionV2(offer, list, proven.id, V2.DEFAULT_CHUNK, summary, tokenFor(req.session, proven.id), dir)
        offer.v2 = s
        offer.declaredTotal = summary.bytes
        offer.timeoutMs = pendingMs
        addEntries(s, req.files)
        if (!req.more) completeManifest(s)
        when (rx.registerOffer(offer)) {
            "busy" -> throw ProtocolException(429, "busy", "This device already has sends waiting for an answer. Try again after it answers.")
            "duplicate" -> throw ProtocolException(409, "duplicate", "That session ID is already in use")
        }
        if (summary.bytes > (try { rx.newStore().freeSpace() } catch (e: Exception) { 0L })) {
            rx.finishOffer(offer, "declined")
            throw ProtocolException(507, "no_space", "Not enough free space on this device")
        }
        if (!dir.isDirectory && !dir.mkdirs()) {
            rx.finishOffer(offer, "declined")
            throw ProtocolException(500, "failed", "Couldn't make room for the parts of the files")
        }
        s.journal.append('S', ProtocolJson.encodeToString(JournalSession.serializer(),
            JournalSession(req.sender, proven.id, summary, text, s.chunkSize, offer.created, x.remoteIp)))
        if (req.files.isNotEmpty()) s.journal.append('F', ProtocolJson.encodeToString(ListSerializer(FileEntry.serializer()), req.files))
        if (s.manifestComplete) s.journal.append('M')
        if (summary.count <= limits.manifestPage && req.files.isNotEmpty()) rx.markAlreadyHere(offer)
        rx.fire { onOffer(offer) }
        sendState(x, s, withToken = true)
    }

    /** Checks a page of the list as a whole, then adds it; a page with one bad entry changes nothing. */
    private fun addEntries(s: SessionV2, page: List<FileEntry>) {
        val seen = HashSet<String>()
        var bytes = 0L
        for (e in page) {
            if (!V2.FILE_ID.matches(e.id)) throw ProtocolException(400, "bad_files", "every entry needs an id of 1 to 64 letters, digits, - or _")
            if (e.id in s.ids || !seen.add(e.id)) throw ProtocolException(400, "bad_files", "\"${Names.shown(e.name)}\": every entry needs its own id.")
            Names.problemV2(e.name)?.let { throw ProtocolException(400, "bad_files", "\"${Names.shown(e.name)}\": $it.") }
            if (e.kind != null && e.kind != "file" && e.kind != FileEntry.KIND_DIR) {
                throw ProtocolException(400, "bad_files", "\"${Names.shown(e.name)}\": the kind is not file or dir.")
            }
            if (e.size < 0 || (e.isDir && e.size != 0L)) throw ProtocolException(400, "bad_files", "\"${Names.shown(e.name)}\": the size is not valid.")
            if (!e.isDir && V2.chunkCount(e.size, s.chunkSize) > MAX_CHUNKS) {
                throw ProtocolException(400, "bad_files", "\"${Names.shown(e.name)}\": the file is too large.")
            }
            bytes = try {
                Math.addExact(bytes, e.size)
            } catch (x: ArithmeticException) {
                throw ProtocolException(400, "bad_files", "the sizes are too large")
            }
        }
        if (s.entries + page.size > maxEntries) throw ProtocolException(413, "too_many", "More entries than this device takes in one send")
        val added = ArrayList<IncomingFile>(page.size)
        for (e in page) {
            s.ids += e.id
            val safe = Names.safeFromV2(e.name)
            if (e.isDir) {
                s.dirs += safe
            } else {
                val f = IncomingFile(e.id, safe, e.size, e.mtime?.takeIf { it > 0 }?.div(1000)?.takeIf { it > 0 })
                f.mtimeMs = e.mtime?.takeIf { it > 0 }
                s.files[e.id] = f
                added += f
            }
        }
        s.list.addAll(added)
        s.entries += page.size
        s.bytes += bytes
    }

    /** The last page has arrived: the list must add up to the summary the sender gave first. */
    private fun completeManifest(s: SessionV2) {
        if (s.entries != s.summary.count || s.bytes != s.summary.bytes) {
            throw ProtocolException(400, "summary_mismatch", "The list does not match its summary")
        }
        s.manifestComplete = true
    }

    private fun sendState(x: Exchange, s: SessionV2, withToken: Boolean) =
        x.sendJson(if (s.offer.state == "waiting") 202 else 200, SessionState.serializer(), stateOf(s, withToken, files = true))

    private fun stateOf(s: SessionV2, withToken: Boolean, files: Boolean): SessionState {
        val o = s.offer
        val now = System.currentTimeMillis()
        val state = if (o.state == "waiting") "pending" else o.state
        val fileStates = if (!files) null else HashMap<String, FileState>().also { map ->
            for (f in s.list) {
                val st = synchronized(f) {
                    when {
                        f.state == "done" -> FileState("done", name = f.savedName)
                        f.skipReason != null -> FileState("skipped", reason = f.skipReason)
                        f.chunks?.count?.let { it > 0 } == true -> FileState("partial", chunks = f.chunks!!.ranges())
                        else -> null
                    }
                }
                if (st != null) map[f.id] = st
            }
        }?.takeIf { it.isNotEmpty() }
        return SessionState(
            session = o.id, state = state, version = s.version, chunkSize = s.chunkSize, inOrder = false,
            manifestComplete = s.manifestComplete,
            expiresIn = if (o.state == "waiting") maxOf(0L, (o.created + pendingMs - now) / 1000) else null,
            wanted = if (o.state == "accepted" || o.state == "done") s.wanted?.let { SessionState.wantedList(it) } ?: SessionState.wantedAll() else null,
            files = fileStates,
            token = if (withToken) s.token else null,
        )
    }

    // --- reading a session

    private fun poll(x: Exchange, s: SessionV2) {
        val after = x.query["after"]?.toLongOrNull()
        val wait = (x.query["wait"]?.toLongOrNull() ?: 0L).coerceIn(0L, MAX_WAIT_S)
        if (after != null && wait > 0) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(wait)
            synchronized(s.monitor) {
                while (s.version <= after) {
                    val left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
                    if (left <= 0) break
                    try {
                        s.monitor.wait(left)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
        // `files=none` (an addition to the spec): leave the per-file list out, which can be very long during a big send
        x.sendJson(200, SessionState.serializer(), stateOf(s, withToken = false, files = x.query["files"] != "none"))
    }

    private fun cancel(x: Exchange, s: SessionV2) {
        rx.cancel(s.offer.id)
        x.sendJson(200, OkReply.serializer(), OkReply())
    }

    /** A session nobody answered in time becomes `expired`. */
    fun expireIfDue(s: SessionV2) {
        if (s.offer.state == "waiting" && System.currentTimeMillis() - s.offer.created > pendingMs) rx.finishOffer(s.offer, "expired")
    }

    // --- the file list

    private fun manifest(x: Exchange, s: SessionV2) {
        val page = readJson(x, ManifestPage.serializer())
        if (s.manifestComplete) throw ProtocolException(409, "manifest_complete", "The list is complete")
        if (page.files.size > limits.manifestPage) throw ProtocolException(400, "bad_files", "A page has at most ${limits.manifestPage} entries")
        synchronized(s.offer) {
            if (s.offer.state != "waiting" && s.offer.state != "accepted") throw endedError(s)
            try {
                addEntries(s, page.files)
                if (!page.more) completeManifest(s)
            } catch (e: ProtocolException) {
                if (e.code == "summary_mismatch") rx.finishOffer(s.offer, "cancelled")
                throw e
            }
        }
        if (page.files.isNotEmpty()) s.journal.append('F', ProtocolJson.encodeToString(ListSerializer(FileEntry.serializer()), page.files))
        if (s.manifestComplete) {
            s.journal.append('M')
            s.bump()
            maybeFinish(s)
        }
        x.sendJson(200, ManifestReply.serializer(), ManifestReply(true, s.entries))
    }

    // --- the person's answer (called by Receiver.answer)

    fun answer(offer: Offer, s: SessionV2, accept: Boolean, skip: Boolean, wanted: Set<String>? = null): Boolean {
        synchronized(offer) {
            if (offer.state != "waiting") return false
            if (!accept) {
                offer.state = "declined"
            } else {
                offer.store = rx.newStore()
                s.wanted = wanted
                for (f in s.list) {
                    if (!s.wantedFile(f)) {
                        f.skipped = true // not chosen: it does not count in what is received
                    } else if (skip && f.alreadyHere) {
                        f.skipped = true
                        f.skipReason = "already_here"
                        s.journal.append('K', "${f.id} already_here")
                    }
                }
                offer.state = "accepted"
                offer.acceptedAt = System.currentTimeMillis()
                offer.lastSeen = System.nanoTime()
                s.journal.append('A', ProtocolJson.encodeToString(JournalAnswer.serializer(), JournalAnswer(wanted?.toList(), offer.acceptedAt)))
            }
        }
        if (!accept) {
            rx.fire { onFinished(offer) }
            ended(s)
        } else {
            maybeFinish(s)
        }
        s.bump()
        offer.answered.countDown()
        return true
    }

    /** Called when the session reached a state with nothing more to receive: frees the parts and the journal's file handle. */
    fun ended(s: SessionV2) {
        s.bump()
        s.journal.append('X', s.offer.state)
        s.journal.close()
        // parts of unfinished files are thrown away; files already saved stay
        s.dir.listFiles { f -> f.name.endsWith(PART) }?.forEach { it.delete() }
        if (s.offer.state != "accepted") File(s.dir, SessionV2.JOURNAL).delete().also { s.dir.delete() }
    }

    /** Done when the whole list is here and every file the person chose is saved or left out. */
    private fun maybeFinish(s: SessionV2) {
        val o = s.offer
        if (o.state != "accepted" || !s.manifestComplete) return
        if (s.list.any { f -> !f.skipped && f.state != "done" }) return
        for (dir in s.dirs) {
            try {
                (o.store ?: rx.newStore()).makeDir(dir)
            } catch (e: Exception) {
                // an empty folder is not worth failing the send for
            }
        }
        rx.finishOffer(o, "done")
    }

    // --- uploading

    /** Uploads need an accepted session; a finished one answers with its end state, as the spec's table says. */
    private fun endedError(s: SessionV2): ProtocolException = when (s.offer.state) {
        "waiting" -> ProtocolException(409, "not_accepted", "The person has not answered yet. Wait on the long-poll.")
        "declined" -> ProtocolException(410, "declined", "The session was declined")
        "expired" -> ProtocolException(410, "expired", "Nobody answered in time")
        "cancelled" -> ProtocolException(410, "cancelled", "The session was cancelled")
        else -> ProtocolException(409, "finished", "The session is over")
    }

    private fun uploadable(s: SessionV2, fid: String): IncomingFile {
        if (s.offer.state != "accepted") throw endedError(s)
        val f = s.files[fid] ?: throw ProtocolException(404, "no_file", "That file is not in the list (yet)")
        if (!s.wantedFile(f)) throw ProtocolException(409, "not_wanted", "The person did not accept that file")
        if (f.state == "done" || f.skipReason != null) throw ProtocolException(409, "finished", "That file is already saved or left out")
        return f
    }

    private fun partFile(s: SessionV2, f: IncomingFile) = File(s.dir, f.id + PART)

    private fun putChunk(x: Exchange, s: SessionV2, fid: String, nText: String) {
        val f = uploadable(s, fid)
        val total = V2.chunkCount(f.size, s.chunkSize)
        val n = nText.toLongOrNull()
        if (n == null || n < 0 || n >= total) throw ProtocolException(400, "bad_chunk", "That is not a chunk of this file")
        val (offset, length) = V2.chunkSpan(n, f.size, s.chunkSize)
        val declared = x.contentLength ?: throw ProtocolException(411, "length_required", "Content-Length is required")
        if (declared != length) throw ProtocolException(400, "wrong_size", "Content-Length must be $length for this chunk")
        val digest = x.headers["kl-chunk-sha256"]?.trim()?.lowercase()
        if (digest == null || !HEX64.matches(digest)) throw ProtocolException(400, "bad_digest", "KL-Chunk-SHA256 must be 64 hex digits")

        val have = synchronized(f) { f.digests?.getOrNull(n.toInt()) }
        x.bodyRead = true
        if (have != null) { // a chunk sent twice is safe
            val got = hashAndDiscard(x, length) ?: return x.hangUp()
            if (got != digest) throw ProtocolException(422, "digest_mismatch", "The bytes do not match KL-Chunk-SHA256")
            if (got != have) throw ProtocolException(409, "chunk_changed", "The receiver has this chunk with another digest: the file changed")
            return x.sendJson(200, ChunkReply.serializer(), ChunkReply(true, fid, n))
        }

        val md = MessageDigest.getInstance("SHA-256")
        val part = partFile(s, f)
        try {
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(BUF)
                var left = length
                while (left > 0) {
                    val r = try {
                        x.body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    } catch (e: IOException) {
                        -1
                    }
                    if (r < 0) return x.hangUp() // the sender went away in the middle: nothing is recorded
                    raf.write(buf, 0, r)
                    md.update(buf, 0, r)
                    left -= r
                }
                raf.fd.sync() // the data is on disk before the journal says it arrived
            }
        } catch (e: IOException) {
            if (isDiskFull(e)) throw ProtocolException(507, "no_space", "This device ran out of space")
            throw ProtocolException(500, "failed", "Couldn't write the chunk: ${e.message}")
        }
        if (md.digest().toHexLower() != digest) throw ProtocolException(422, "digest_mismatch", "The bytes do not match KL-Chunk-SHA256")

        var firstChunk = false
        synchronized(f) {
            if (f.chunks == null) {
                f.chunks = ChunkSet()
                f.digests = arrayOfNulls(total.toInt())
                firstChunk = true
            }
            if (f.digests!![n.toInt()] == null) {
                f.chunks!!.add(n)
                f.digests!![n.toInt()] = digest
                f.received += length
            }
            if (f.state == "waiting") f.state = "receiving"
        }
        s.journal.append('C', "$fid $n $digest")
        if (firstChunk) s.bump()
        progress(s, f)
        x.sendJson(201, ChunkReply.serializer(), ChunkReply(true, fid, n))
    }

    /** Reads [length] bytes and gives their SHA-256, or null when the sender went away. */
    private fun hashAndDiscard(x: Exchange, length: Long): String? {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUF)
        var left = length
        while (left > 0) {
            val r = try {
                x.body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            } catch (e: IOException) {
                -1
            }
            if (r < 0) return null
            md.update(buf, 0, r)
            left -= r
        }
        return md.digest().toHexLower()
    }

    /** Tells the app about progress at most 4 times a second. */
    private fun progress(s: SessionV2, f: IncomingFile) {
        val now = System.nanoTime()
        if (now - s.lastProgress < 250_000_000L) return
        s.lastProgress = now
        s.offer.lastSeen = now
        f.lastBytesAt = now
        rx.fire { onProgress(s.offer, f) }
    }

    private fun doneFile(x: Exchange, s: SessionV2, fid: String) {
        val req = readJson(x, DoneRequest.serializer())
        val f = s.files[fid]
        if (f != null && f.state == "done") { // calling done again for a saved file gives the same reply
            return x.sendJson(200, DoneReply.serializer(), DoneReply(true, f.savedName.orEmpty()))
        }
        val file = uploadable(s, fid)
        if (req.alg != V2.DIGEST_ALG || !HEX64.matches(req.digest.lowercase())) {
            throw ProtocolException(400, "bad_digest", "alg must be ${V2.DIGEST_ALG} and digest 64 hex digits")
        }
        val total = V2.chunkCount(file.size, s.chunkSize)
        val saved = try {
            synchronized(file) {
                val set = file.chunks ?: ChunkSet()
                if (!set.isComplete(total)) throw MissingChunks(set.missing(total))
                val digests = (0 until total.toInt()).map { file.digests!![it]!! }
                if (V2.treeDigest(digests, s.chunkSize) != req.digest.lowercase()) {
                    resetFile(s, file) // the file changed while it was sent: start it again
                    throw ProtocolException(422, "digest_mismatch", "The digests do not agree: send the file again from the start")
                }
                finalizeFile(s, file, if (total > 0) partFile(s, file) else null, req.digest.lowercase())
            }
        } catch (e: MissingChunks) {
            return x.fail(409, "missing_chunks", "Some chunks haven't arrived", missing = e.ranges)
        }
        s.bump()
        maybeFinish(s)
        x.sendJson(200, DoneReply.serializer(), DoneReply(true, saved))
    }

    /** Moves a whole, checked file into the receive store and records it. Returns the name it was saved as. */
    private fun finalizeFile(s: SessionV2, f: IncomingFile, part: File?, digest: String?): String {
        val store = s.offer.store ?: rx.newStore()
        val up = try {
            store.open(f.name, f.size)
        } catch (e: IOException) {
            throw if (isDiskFull(e)) ProtocolException(507, "no_space", "This device ran out of space")
            else ProtocolException(500, "failed", "Couldn't save ${Names.shown(f.name)}: ${e.message}")
        }
        val saved = try {
            if (part != null) FileInputStream(part).use { it.copyTo(up.output, BUF) }
            up.output.close()
            up.commit()
        } catch (e: IOException) {
            up.abort()
            throw if (isDiskFull(e)) ProtocolException(507, "no_space", "This device ran out of space")
            else ProtocolException(500, "failed", "Couldn't save ${Names.shown(f.name)}: ${e.message}")
        }
        try {
            store.remember(f.name, saved, f.size, f.mtimeMs?.div(1000))
        } catch (e: Exception) {
            // the date and the index are extras; the file is saved
        }
        part?.delete()
        f.savedName = saved
        f.sha256 = digest
        f.state = "done"
        f.received = f.size
        s.journal.append('D', "${f.id} $saved")
        rx.fire { onFile(s.offer, f) }
        return saved
    }

    private fun resetFile(s: SessionV2, f: IncomingFile) {
        synchronized(f) {
            f.chunks = null
            f.digests = null
            f.received = 0
            f.state = "waiting"
        }
        partFile(s, f).delete()
        s.journal.append('R', f.id)
        s.bump()
    }

    private class MissingChunks(val ranges: List<List<Long>>) : Exception()

    // --- batches

    private fun batch(x: Exchange, s: SessionV2) {
        // a batch sent again after a lost reply may find the session already done: every file then answers "saved" again
        if (s.offer.state != "accepted" && s.offer.state != "done") throw endedError(s)
        val length = x.contentLength ?: throw ProtocolException(411, "length_required", "Content-Length is required")
        if (limits.batchMaxFile <= 0) throw ProtocolException(400, "bad_batch", "This device takes no batches")
        if (length > limits.batchMaxBytes) throw ProtocolException(413, "too_large", "The batch is over ${limits.batchMaxBytes} bytes")
        x.bodyRead = true
        val results = LinkedHashMap<String, BatchResult>()
        var count = 0
        try {
            V2.readBatch(x.body, limits.batchMaxFile) { h, bytes ->
                if (++count > limits.batchMaxFiles) throw V2.BatchException("too many files in one batch")
                results[h.file] = saveBatchFile(s, h.file, h.size, h.sha256.lowercase(), bytes)
            }
        } catch (e: V2.BatchException) {
            // what was saved before the break stays saved; the sender reads the session to see which
            throw ProtocolException(400, "bad_batch", e.message ?: "The batch breaks the format")
        }
        s.bump()
        maybeFinish(s)
        x.sendJson(200, BatchReply.serializer(), BatchReply(results))
    }

    private fun saveBatchFile(s: SessionV2, fid: String, size: Long, sha: String, bytes: ByteArray): BatchResult {
        val f = s.files[fid] ?: return BatchResult(false, error = "no_file")
        if (!s.wantedFile(f)) return BatchResult(false, error = "not_wanted")
        synchronized(f) {
            if (f.state == "done") return BatchResult(true, name = f.savedName)
            if (f.skipReason != null) return BatchResult(false, error = "finished")
            if (size != f.size) return BatchResult(false, error = "wrong_size")
            if (f.chunks?.count?.let { it > 0 } == true) return BatchResult(false, error = "chunks_sent")
            if (!HEX64.matches(sha) || V2.chunkDigest(bytes) != sha) return BatchResult(false, error = "digest_mismatch")
            return try {
                val store = s.offer.store ?: rx.newStore()
                val up = store.open(f.name, f.size)
                val saved = try {
                    up.output.write(bytes)
                    up.output.close()
                    up.commit()
                } catch (e: IOException) {
                    up.abort()
                    throw e
                }
                try {
                    store.remember(f.name, saved, f.size, f.mtimeMs?.div(1000))
                } catch (e: Exception) {
                    // an extra
                }
                f.savedName = saved
                f.sha256 = sha
                f.state = "done"
                f.received = f.size
                s.journal.append('D', "${f.id} $saved")
                rx.fire { onProgress(s.offer, f) }
                BatchResult(true, name = saved)
            } catch (e: IOException) {
                BatchResult(false, error = if (isDiskFull(e)) "no_space" else "failed")
            }
        }
    }

    // --- dropping and restarting one file

    private fun dropFile(x: Exchange, s: SessionV2, fid: String) {
        val f = s.files[fid] ?: throw ProtocolException(404, "no_file", "That file is not in the list")
        synchronized(f) {
            if (f.state == "done") throw ProtocolException(409, "finished", "That file is already saved")
            f.skipped = true
            f.skipReason = "dropped"
            f.chunks = null
            f.digests = null
        }
        partFile(s, f).delete()
        s.journal.append('K', "$fid dropped")
        s.bump()
        maybeFinish(s)
        x.sendJson(200, OkReply.serializer(), OkReply())
    }

    private fun restartFile(x: Exchange, s: SessionV2, fid: String) {
        val f = s.files[fid] ?: throw ProtocolException(404, "no_file", "That file is not in the list")
        if (f.state == "done") throw ProtocolException(409, "finished", "That file is already saved")
        resetFile(s, f)
        x.sendJson(200, OkReply.serializer(), OkReply())
    }

    // --- the sweeper and a restart

    /** Called every 30 seconds by [Receiver]: sessions nobody answered end, and sessions the sender left for a week end. */
    fun sweep(o: Offer, idleSeconds: Long) {
        val s = o.v2 ?: return
        expireIfDue(s)
        if (o.state == "accepted" && idleSeconds > TimeUnit.DAYS.toSeconds(RETAIN_DAYS)) rx.finishOffer(o, "cancelled")
    }

    /** After a restart: sessions that were accepted and not finished come back from their journals, so a sender can carry on. */
    fun recover() {
        val dirs = partsDir.listFiles { f -> f.isDirectory } ?: return
        for (dir in dirs) {
            try {
                if (!recoverOne(dir)) deleteDir(dir)
            } catch (e: Exception) {
                deleteDir(dir)
            }
        }
    }

    private fun recoverOne(dir: File): Boolean {
        if (!V2.TOKEN.matches(dir.name)) return false
        val file = File(dir, SessionV2.JOURNAL)
        val lines = Journal.read(file)
        if (lines.isEmpty() || lines.any { it.first == 'X' } || lines.none { it.first == 'A' }) return false
        val ageMs = System.currentTimeMillis() - file.lastModified()
        if (ageMs > TimeUnit.DAYS.toMillis(RETAIN_DAYS)) return false
        val head = ProtocolJson.decodeFromString(JournalSession.serializer(), lines.first { it.first == 'S' }.second)
        val list = CopyOnWriteArrayList<IncomingFile>()
        val sender = Sender(head.senderId, head.sender.name.take(128), head.sender.model.take(128), head.sender.app.take(128))
        val offer = Offer(dir.name, sender, head.ip, list, head.text, "", skipOk = true, created = head.created)
        val s = SessionV2(offer, list, head.senderId, head.chunk, head.summary, tokenFor(dir.name, head.senderId), dir)
        offer.v2 = s
        offer.declaredTotal = head.summary.bytes
        for ((tag, payload) in lines) {
            when (tag) {
                'F' -> addEntries(s, ProtocolJson.decodeFromString(ListSerializer(FileEntry.serializer()), payload))
                'M' -> s.manifestComplete = true
                'A' -> {
                    val a = ProtocolJson.decodeFromString(JournalAnswer.serializer(), payload)
                    s.wanted = a.wanted?.toSet()
                    offer.acceptedAt = a.at
                }
                'C' -> {
                    val p = payload.split(' ')
                    val f = s.files[p[0]] ?: continue
                    val n = p[1].toLong()
                    val total = V2.chunkCount(f.size, s.chunkSize)
                    if (n !in 0 until total) continue
                    if (f.chunks == null) {
                        f.chunks = ChunkSet()
                        f.digests = arrayOfNulls(total.toInt())
                    }
                    if (f.digests!![n.toInt()] == null) {
                        f.chunks!!.add(n)
                        f.digests!![n.toInt()] = p[2]
                        f.received += V2.chunkSpan(n, f.size, s.chunkSize).second
                        f.state = "receiving"
                    }
                }
                'D' -> {
                    val id = payload.substringBefore(' ')
                    val f = s.files[id] ?: continue
                    f.savedName = payload.substringAfter(' ')
                    f.state = "done"
                    f.received = f.size
                    f.chunks = null
                    f.digests = null
                }
                'K' -> {
                    val f = s.files[payload.substringBefore(' ')] ?: continue
                    f.skipped = true
                    f.skipReason = payload.substringAfter(' ')
                }
                'R' -> s.files[payload]?.let { f ->
                    f.chunks = null
                    f.digests = null
                    f.received = 0
                    f.state = "waiting"
                }
            }
        }
        for (f in list) {
            if (!s.wantedFile(f)) f.skipped = true
            // a chunk counts only if its bytes are really in the part (the phone may have stopped before they were written)
            val chunks = f.chunks
            if (f.state != "done" && chunks != null) {
                val part = partFile(s, f)
                val have = if (part.isFile) part.length() else 0L
                val total = V2.chunkCount(f.size, s.chunkSize)
                for (n in 0 until total) {
                    if (n in chunks && have < V2.chunkSpan(n, f.size, s.chunkSize).let { it.first + it.second }) {
                        chunks.remove(n)
                        f.digests!![n.toInt()] = null
                        f.received -= V2.chunkSpan(n, f.size, s.chunkSize).second
                    }
                }
                if (chunks.count == 0) {
                    f.chunks = null
                    f.digests = null
                    f.state = "waiting"
                }
            }
        }
        offer.state = "accepted"
        offer.store = rx.newStore()
        rx.restore(offer)
        return true
    }

    fun stop() {
        for (o in rx.allOffers()) o.v2?.journal?.close()
    }

    private fun deleteDir(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    companion object {
        const val PART = ".part"
        private const val BUF = 64 * 1024
        private const val MAX_JSON = 8L * 1024 * 1024
        private const val MAX_WAIT_S = 25L
        private const val MAX_CHUNKS = 500_000L
        private const val RETAIN_DAYS = 7L
        const val MAX_ENTRIES = 200_000
        private val HEX64 = Regex("[0-9a-f]{64}")

        /** What a phone offers: 4 connections, 8 MiB chunks, batches of up to 1,000 files of up to 1 MiB (PROTOCOL-v2.md section 5). */
        val DEFAULT_LIMITS = V2Limits(
            maxStreams = 4, chunkSize = V2.DEFAULT_CHUNK, batchMaxFile = 1L shl 20, batchMaxBytes = 32L shl 20,
            batchMaxFiles = 1000, manifestPage = 5000,
        )

        private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
    }
}

@kotlinx.serialization.Serializable
internal data class JournalSession(
    val sender: SenderV2,
    val senderId: String,
    val summary: Summary,
    val text: String,
    val chunk: Long,
    val created: Long,
    val ip: String,
)

@kotlinx.serialization.Serializable
internal data class JournalAnswer(val wanted: List<String>?, val at: Long)
