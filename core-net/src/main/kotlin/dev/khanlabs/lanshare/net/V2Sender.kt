package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.ApiError
import dev.khanlabs.lanshare.protocol.BatchReply
import dev.khanlabs.lanshare.protocol.ChunkSet
import dev.khanlabs.lanshare.protocol.DoneReply
import dev.khanlabs.lanshare.protocol.DoneRequest
import dev.khanlabs.lanshare.protocol.FileEntry
import dev.khanlabs.lanshare.protocol.InfoV2
import dev.khanlabs.lanshare.protocol.ManifestPage
import dev.khanlabs.lanshare.protocol.ProtocolJson
import dev.khanlabs.lanshare.protocol.SessionCreate
import dev.khanlabs.lanshare.protocol.SessionState
import dev.khanlabs.lanshare.protocol.Summary
import dev.khanlabs.lanshare.protocol.V2
import dev.khanlabs.lanshare.protocol.V2Limits
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import dev.khanlabs.lanshare.protocol.SenderV2 as SenderInfo

/**
 * Sending with protocol v2 (PROTOCOL-v2.md): one session, the list of files in pages, then chunks of big files and batches
 * of small ones over a few connections at once, each chunk checked with SHA-256. A break in the connection is picked up
 * again where it stopped, because the receiver says which chunks it has. It is part of a [SendJob]: it sets the same
 * numbers and states, so the screens and the history need no change.
 *
 * Not built yet: carrying on after this app was closed (the files are shared addresses that may not outlive it), empty
 * folders, and the person choosing which files to take.
 */
internal class V2Sender(private val job: SendJob, private val identity: Identity) {
    private val device get() = job.device
    private val http: OkHttpClient get() = job.clients.pinnedV2(device.id)
    private val sid = job.offerId
    @Volatile private var token = ""
    @Volatile private var chunkSize = V2.DEFAULT_CHUNK
    private var limits: V2Limits = V2Limits(4, V2.DEFAULT_CHUNK, 0, 0, 0, 1)
    private var features: List<String> = emptyList()

    /** The worker threads' shared state. */
    private val lock = Object()
    private val fatal = AtomicReference<Throwable?>()
    @Volatile private var stopping = false
    @Volatile private var remoteEnd: String? = null
    @Volatile private var lastGoodNs = System.nanoTime()
    private val works = ArrayList<FileWork?>() // by item index; null for a file sent in a batch
    private val small = ArrayDeque<Int>()
    private val big = ArrayDeque<FileWork>()
    private val finishing = ArrayDeque<FileWork>()
    private var outstanding = 0 // files not done yet
    private val leftOut = ArrayList<String>() // names the receiver did not take, with why
    @Volatile private var outageActive = false
    private val updateAt = AtomicLong()

    private class FileWork(val index: Int, val item: SendItem, val chunks: Long) {
        val have = ChunkSet() // chunks the receiver has
        val busy = ChunkSet() // chunks being sent right now
        val digests = arrayOfNulls<String>(chunks.toInt().coerceAtLeast(0))
        var next = 0L
        /** The `done` call for this file is waiting in the queue or running. */
        var finishQueued = false
        var restarts = 0
        var damaged = 0
    }

    private sealed class Piece {
        class Chunk(val file: FileWork, val n: Long) : Piece()
        class Batch(val indexes: List<Int>) : Piece()
        class Finish(val file: FileWork) : Piece()
    }

    // --- the whole send

    /** False when the device does not answer on the v2 API after all, so the caller sends with v1. */
    fun run(): Boolean {
        val info = getInfo() ?: return false
        if (!info.receiving) throw SendException(Errors.BY_CODE.getValue("not_receiving").replace("{name}", device.name), "not_receiving")
        limits = info.limits
        features = info.features
        job.update(SendJob.State.WAITING, "Waiting for ${device.name} to accept")
        var state = createSession(info)
        sendLeftOverPages()
        state = waitForAnswer(state) ?: return true
        job.acceptedAt = System.currentTimeMillis()
        job.update(SendJob.State.SENDING, "Sending")
        send(state)
        return true
    }

    /** `DELETE /sessions/{sid}`: tells the receiver the person cancelled. Best effort. */
    fun cancelRemote() {
        if (token.isEmpty()) return
        try {
            val request = Request.Builder().url(u("/sessions/$sid")).header("Authorization", "Bearer $token").delete().build()
            http.newCall(request).execute().close()
        } catch (e: Exception) {
            // the receiver drops the session on its own after a while
        }
    }

    // --- talking to the receiver

    private fun u(path: String): String {
        val h = job.host.let { if (':' in it) "[$it]" else it }
        return "https://$h:${device.port}${V2.API}$path"
    }

    private fun <T> call(request: Request, block: (Response) -> T): T {
        val call = http.newCall(request)
        job.activeCalls += call
        try {
            return call.execute().use(block).also { lastGoodNs = System.nanoTime() }
        } finally {
            job.activeCalls -= call
        }
    }

    private fun authed(path: String) = Request.Builder().url(u(path)).header("Authorization", "Bearer $token")

    private fun refusal(r: Response, body: String): SendException {
        val err = errorOf(body)
        return SendException(Errors.refusal(r.code, err, device.name, job.saved.size, job.fileCount), err?.error ?: "")
    }

    /** `GET /info`: the limits and a nonce. Null when the device has no v2 API. */
    private fun getInfo(): InfoV2? = call(Request.Builder().url(u("/info")).get().build()) { r ->
        val body = r.body.string()
        when (r.code) {
            200 -> ProtocolJson.decodeFromString(InfoV2.serializer(), body)
            404 -> null
            else -> throw refusal(r, body)
        }
    }

    private fun createSession(info: InfoV2): SessionState {
        val entries = job.items.mapIndexed { i, item ->
            FileEntry(i.toString(), item.name, item.size, item.modified.takeIf { it > 0 })
        }
        val pageSize = limits.manifestPage.coerceIn(1, 5000)
        val first = entries.take(pageSize)
        val names = job.items.map { it.name.substringBefore('/') }.distinct().take(5)
        val summary = Summary(entries.size.toLong(), job.items.sumOf { it.size }, names)
        var nonce = info.nonce
        for (attempt in 0..1) {
            val made = DeviceProof.make(identity, device.id, "session", nonce, sid)
            val request = SessionCreate(sid, SenderInfo(identity.id, job.me.name, job.me.model, job.me.app), made.cert, nonce, made.proof,
                summary, first, more = entries.size > first.size, text = job.text.ifEmpty { null })
            val post = Request.Builder().url(u("/sessions"))
                .post(ProtocolJson.encodeToString(SessionCreate.serializer(), request).toRequestBody(JSON)).build()
            val result = call(post) { r ->
                val body = r.body.string()
                when {
                    r.code == 200 || r.code == 202 -> ProtocolJson.decodeFromString(SessionState.serializer(), body)
                    r.code == 403 && errorOf(body)?.error == "bad_nonce" && attempt == 0 -> null
                    else -> throw refusal(r, body)
                }
            }
            if (result != null) {
                token = result.token ?: throw SendException("${device.name} did not give a token for this send.", "failed")
                chunkSize = result.chunkSize
                pending = entries.drop(first.size)
                return result
            }
            nonce = getInfo()?.nonce ?: throw SendException("${device.name} stopped answering on the v2 API.", "failed")
        }
        throw SendException("${device.name} did not accept the proof of who is sending. Send again.", "bad_nonce")
    }

    private var pending: List<FileEntry> = emptyList()

    /** The rest of a long list, in pages (PROTOCOL-v2.md section 6.3). */
    private fun sendLeftOverPages() {
        val pageSize = limits.manifestPage.coerceIn(1, 5000)
        var rest = pending
        while (rest.isNotEmpty()) {
            val page = rest.take(pageSize)
            rest = rest.drop(page.size)
            val body = ProtocolJson.encodeToString(ManifestPage.serializer(), ManifestPage(page, more = rest.isNotEmpty()))
            retrying {
                call(authed("/sessions/$sid/manifest").post(body.toRequestBody(JSON)).build()) { r ->
                    val text = r.body.string()
                    if (r.code != 200) throw refusal(r, text)
                }
            }
        }
        pending = emptyList()
    }

    private fun getState(wait: Int = 0, after: Long? = null, files: Boolean = true): SessionState {
        val query = buildList {
            if (after != null) add("after=$after")
            if (wait > 0) add("wait=$wait")
            if (!files) add("files=none")
        }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        return call(authed("/sessions/$sid$query").get().build()) { r ->
            val body = r.body.string()
            if (r.code != 200) throw refusal(r, body)
            ProtocolJson.decodeFromString(SessionState.serializer(), body)
        }
    }

    /** Waits (with the long-poll) for the person's answer. Null when the send ended there: the job has said how. */
    private fun waitForAnswer(first: SessionState): SessionState? {
        var state = first
        var version = state.version
        while (true) {
            when (state.state) {
                "accepted", "done" -> return state
                "declined" -> {
                    job.update(SendJob.State.DECLINED, Errors.refusal(403, ApiError("declined"), device.name))
                    return null
                }
                "expired" -> {
                    job.update(SendJob.State.DECLINED, Errors.refusal(408, ApiError("timeout"), device.name))
                    return null
                }
                "cancelled" -> throw SendException(Errors.refusal(410, ApiError("cancelled"), device.name), "cancelled")
            }
            if (job.cancelled) throw IOException("cancelled")
            state = retrying { getState(wait = LONG_POLL_S, after = version, files = false) }
            version = maxOf(version, state.version)
        }
    }

    /** Runs [block], and again after a pause when the connection breaks, until it has been broken for 3 minutes. */
    private fun <T> retrying(block: () -> T): T {
        var failingSince = 0L
        var step = 0
        while (true) {
            try {
                return block().also { if (job.reconnecting) back() }
            } catch (e: IOException) {
                if (job.cancelled || e is SendException || job.fileFailure != null || Clients.mismatch(e) != null) throw e
                if (failingSince == 0L) failingSince = System.nanoTime()
                if (System.nanoTime() - failingSince > WINDOW_NS) throw e
                pauseFor(RETRY_DELAYS_S[minOf(step++, RETRY_DELAYS_S.size - 1)])
                if (job.cancelled) throw IOException("cancelled")
                try {
                    job.pickHost()
                } catch (x: Exception) {
                    if (job.cancelled || x !is IOException || Clients.mismatch(x) != null) throw x
                }
            }
        }
    }

    private fun pauseFor(seconds: Long) {
        job.retrySignal.drainPermits()
        job.reconnecting = true
        job.resumes++
        job.update(job.state, "The connection to ${device.name} dropped. Trying again in $seconds seconds.")
        job.retrySignal.tryAcquire(seconds, TimeUnit.SECONDS)
    }

    private fun back() {
        job.reconnecting = false
        job.update(job.state, if (job.state == SendJob.State.WAITING) "Waiting for ${device.name} to accept" else "Sending")
    }

    // --- sending

    private fun send(accepted: SessionState) {
        absorb(retrying { getState(files = true) })
        if (job.items.isEmpty() || outstanding == 0) return finish()
        planWork()
        job.update(SendJob.State.SENDING, "Sending")
        val workers = ArrayList<Thread>()
        fun startWorker() {
            val t = Thread(::worker, "klshare-v2-send-${workers.size + 1}").apply { isDaemon = true }
            synchronized(workers) { workers += t }
            t.start()
        }
        val poller = Thread(::poll, "klshare-v2-poll").apply { isDaemon = true }
        poller.start()
        repeat(minOf(START_STREAMS, limits.maxStreams.coerceAtLeast(1))) { startWorker() }
        // add a connection while the speed keeps rising by 10% or more, up to what the receiver allows
        var lastSpeed = 0.0
        var lastCheck = System.nanoTime()
        try {
            while (true) {
                synchronized(lock) {
                    if (outstanding == 0 || fatal.get() != null || stopping) return@synchronized
                    lock.wait(300)
                }
                if (outstanding == 0 || fatal.get() != null || stopping || job.cancelled) break
                val now = System.nanoTime()
                if (now - lastCheck > RAMP_NS) {
                    val speed = job.speed
                    if (speed >= lastSpeed * 1.1 && synchronized(workers) { workers.size } < limits.maxStreams && hasWaitingWork()) startWorker()
                    lastSpeed = speed
                    lastCheck = now
                }
            }
        } finally {
            stopping = true
            synchronized(lock) { lock.notifyAll() }
            poller.interrupt()
            synchronized(job.activeCalls) { job.activeCalls.toList() }.forEach { if (fatal.get() != null || remoteEnd != null || job.cancelled) it.cancel() }
            for (t in synchronized(workers) { workers.toList() }) t.join(5_000)
            poller.join(2_000)
        }
        if (job.cancelled) throw IOException("cancelled")
        remoteEnd?.let { end ->
            val code = if (end == "declined") "declined" else if (end == "expired") "timeout" else "cancelled"
            throw SendException(Errors.refusal(410, ApiError(code), device.name, job.saved.size, job.fileCount), code)
        }
        fatal.get()?.let { throw it }
        finish()
    }

    /**
     * Takes in what the receiver says about the files: the ones it has (a resumed send), the ones already there, the ones it
     * left out or the person did not choose. Called at the start and again after a repeat that the receiver answered with
     * "finished". Not for use while workers hold the lock for a file this changes.
     */
    private fun absorb(state: SessionState) = synchronized(lock) {
        val wanted = state.wantedIds
        val files = state.files.orEmpty()
        for (i in job.items.indices) {
            if (i in job.finished) continue
            val item = job.items[i]
            val fs = files[i.toString()]
            when {
                wanted != null && i.toString() !in wanted -> leftOutNow(i, "not chosen")
                fs?.state == "done" -> {
                    job.sent += item.size
                    job.verified++
                    markDoneLocked(i, fs.name ?: item.name)
                }
                fs?.state == "skipped" && fs.reason == "already_here" -> {
                    job.finished += i
                    job.alreadyThere += i
                    job.skipped++
                    job.skippedBytes += item.size
                    job.total -= item.size
                    item.release()
                }
                fs?.state == "skipped" -> leftOutNow(i, fs.reason ?: "left out")
            }
        }
        outstanding = job.items.indices.count { it !in job.finished }
        resumedChunks = files.filterValues { it.state == "partial" }
        lock.notifyAll()
    }

    private var resumedChunks: Map<String, dev.khanlabs.lanshare.protocol.FileState> = emptyMap()

    private fun leftOutNow(i: Int, why: String) {
        if (!job.finished.add(i)) return
        job.leftOut += i
        job.total -= job.items[i].size
        leftOut += "${job.items[i].name} ($why)"
        job.items[i].release()
    }

    private fun planWork() {
        val batchOk = limits.batchMaxFile > 0 && "batch" in features
        for (i in job.items.indices) {
            works += null
        }
        for ((i, item) in job.items.withIndex()) {
            if (i in job.finished) continue
            if (batchOk && item.size <= limits.batchMaxFile && resumedChunks[i.toString()] == null) {
                small += i
            } else {
                val fw = FileWork(i, item, V2.chunkCount(item.size, chunkSize))
                resumedChunks[i.toString()]?.chunks?.let { ranges ->
                    ChunkSet.fromRanges(ranges).let { have -> for (n in 0 until fw.chunks) if (n in have) fw.have.add(n) }
                }
                works[i] = fw
                big += fw
                if (fw.chunks == 0L || fw.have.isComplete(fw.chunks)) {
                    fw.finishQueued = true
                    finishing += fw
                }
            }
        }
    }

    private fun hasWaitingWork(): Boolean = synchronized(lock) { small.isNotEmpty() || big.isNotEmpty() }

    // --- the worker threads

    private fun worker() {
        while (!stopping && fatal.get() == null && !job.cancelled) {
            val piece = take() ?: return
            try {
                when (piece) {
                    is Piece.Chunk -> sendChunk(piece)
                    is Piece.Batch -> sendBatch(piece)
                    is Piece.Finish -> finishFile(piece.file)
                }
            } catch (e: Throwable) {
                giveBack(piece)
                if (job.cancelled || stopping) return
                if (e is IOException && e !is SendException && job.fileFailure == null && Clients.mismatch(e) == null) {
                    if (outage(e)) continue
                    return
                }
                fatal.compareAndSet(null, e)
                synchronized(lock) { lock.notifyAll() }
                return
            }
        }
    }

    /** The next piece of work, waiting while another thread's piece may still give more. Null when there is nothing left to do. */
    private fun take(): Piece? {
        synchronized(lock) {
        while (true) {
            if (stopping || fatal.get() != null || job.cancelled) return null
            finishing.removeFirstOrNull()?.let { return Piece.Finish(it) }
            if (small.isNotEmpty()) {
                val picked = ArrayList<Int>()
                var bytes = 0L
                while (small.isNotEmpty() && picked.size < limits.batchMaxFiles.coerceAtLeast(1) &&
                    (picked.isEmpty() || bytes + job.items[small.first()].size <= BATCH_BYTES.coerceAtMost(limits.batchMaxBytes - 4096))) {
                    val i = small.removeFirst()
                    picked += i
                    bytes += job.items[i].size
                }
                return Piece.Batch(picked)
            }
            // chunks, roughly in order: the current file first, then the next ones while its last chunks are in flight
            for (fw in big) {
                var n = fw.next
                while (n < fw.chunks && (n in fw.have || n in fw.busy)) n++
                if (n < fw.chunks) {
                    fw.next = n + 1
                    fw.busy.add(n)
                    return Piece.Chunk(fw, n)
                }
            }
            if (outstanding == 0) return null
            lock.wait(200)
        }
        }
    }

    /** A piece of work that did not get done goes back for another try, or for another thread. */
    private fun giveBack(piece: Piece) = synchronized(lock) {
        when (piece) {
            is Piece.Chunk -> {
                piece.file.busy.remove(piece.n)
                if (piece.n < piece.file.next) piece.file.next = piece.n
            }
            is Piece.Batch -> for (i in piece.indexes) if (i !in job.finished) small.addFirst(i)
            is Piece.Finish -> finishing.addFirst(piece.file) // still queued: another try, by this or another thread
        }
        lock.notifyAll()
    }

    /**
     * The connection broke. One thread (the leader) waits and asks the receiver again, with the pauses of v1: 2, 4, 8, 15, then
     * 30 seconds, giving up when nothing has worked for 3 minutes; the others wait for it. True when it is worth trying again.
     */
    private fun outage(first: IOException): Boolean {
        val leader = synchronized(this) {
            if (outageActive) false else { outageActive = true; true }
        }
        if (!leader) {
            while (outageActive && !stopping && fatal.get() == null && !job.cancelled) Thread.sleep(150)
            return fatal.get() == null && !job.cancelled && !stopping
        }
        try {
            var step = 0
            while (true) {
                if (System.nanoTime() - lastGoodNs > WINDOW_NS) {
                    fatal.compareAndSet(null, first)
                    return false
                }
                pauseFor(RETRY_DELAYS_S[minOf(step++, RETRY_DELAYS_S.size - 1)])
                if (job.cancelled || stopping) return false
                try {
                    job.pickHost()
                    val state = getState(files = false)
                    if (state.state in ENDED) {
                        remoteEnd = state.state
                        stopping = true
                        return false
                    }
                    return true
                } catch (x: SendException) {
                    fatal.compareAndSet(null, x)
                    return false
                } catch (x: IOException) {
                    if (Clients.mismatch(x) != null) {
                        fatal.compareAndSet(null, x)
                        return false
                    }
                }
            }
        } finally {
            outageActive = false
            back()
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /** Keeps one long-poll going on its own connection, so a Cancel on the receiver is seen at once. */
    private fun poll() {
        var version = 0L
        while (!stopping && !job.cancelled) {
            try {
                val state = getState(wait = LONG_POLL_S, after = version, files = false)
                version = maxOf(version, state.version)
                if (state.state in ENDED) {
                    remoteEnd = state.state
                    stopping = true
                    synchronized(lock) { lock.notifyAll() }
                    synchronized(job.activeCalls) { job.activeCalls.toList() }.forEach { it.cancel() }
                    return
                }
                if (state.state == "done") return
            } catch (e: Exception) {
                if (stopping || job.cancelled || Thread.currentThread().isInterrupted) return
                try {
                    Thread.sleep(1_000)
                } catch (x: InterruptedException) {
                    return
                }
            }
        }
    }

    // --- chunks

    private fun sendChunk(unit: Piece.Chunk) {
        val fw = unit.file
        val item = fw.item
        job.current = item.name
        val (offset, length) = V2.chunkSpan(unit.n, item.size, chunkSize)
        val data = readChunk(item, offset, length)
        val digest = V2.chunkDigest(data)
        var damaged = 0
        while (true) {
            if (job.cancelled || stopping) throw IOException("cancelled")
            val counted = AtomicLong()
            val request = authed("/sessions/$sid/files/${fw.index}/chunks/${unit.n}")
                .header("KL-Chunk-SHA256", digest)
                .put(countingBody(data, "application/octet-stream".toMediaType(), counted)).build()
            val outcome = try {
                call(request) { r -> chunkOutcome(r, r.body.string(), fw) }
            } catch (e: IOException) {
                job.addSent(-counted.get())
                throw e
            }
            when (outcome) {
                Outcome.OK -> {
                    fw.digests[unit.n.toInt()] = digest
                    synchronized(lock) {
                        fw.have.add(unit.n)
                        fw.busy.remove(unit.n)
                        if (fw.have.isComplete(fw.chunks) && !fw.finishQueued) {
                            fw.finishQueued = true
                            finishing.addLast(fw)
                            lock.notifyAll()
                        }
                    }
                    return
                }
                Outcome.DAMAGED -> { // changed on the way: this chunk is sent again, a few times
                    job.addSent(-counted.get())
                    if (++damaged >= 3) throw SendException("A part of ${item.name} kept arriving damaged on ${device.name}. " +
                        "Check the Wi-Fi on both devices, then send again.", "damaged")
                }
                Outcome.SLOW -> job.addSent(-counted.get())
                Outcome.CHANGED -> {
                    job.addSent(-counted.get())
                    restartFile(fw)
                    return
                }
                Outcome.FINISHED -> {
                    job.addSent(-counted.get())
                    resync()
                    return
                }
            }
        }
    }

    private enum class Outcome { OK, DAMAGED, SLOW, CHANGED, FINISHED }

    private fun chunkOutcome(r: Response, body: String, fw: FileWork): Outcome = when (r.code) {
        200, 201 -> Outcome.OK
        422 -> Outcome.DAMAGED
        503 -> {
            val wait = r.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 15) ?: 2L
            Thread.sleep(wait * 1000)
            Outcome.SLOW
        }
        409 -> when (errorOf(body)?.error) {
            "chunk_changed" -> Outcome.CHANGED
            "finished", "not_wanted" -> Outcome.FINISHED
            else -> throw refusal(r, body)
        }
        else -> throw refusal(r, body)
    }

    /** The file changed while it was sent: the receiver forgets its chunks and the file starts again, once. */
    private fun restartFile(fw: FileWork) {
        if (++fw.restarts > 1) throw SendException("${fw.item.name} changed while it was being sent. Send it again.", "changed")
        val request = authed("/sessions/$sid/files/${fw.index}/chunks").delete().build()
        call(request) { r ->
            val text = r.body.string()
            if (r.code != 200 && r.code != 409) throw refusal(r, text)
        }
        synchronized(lock) {
            fw.have.clear()
            fw.busy.clear()
            for (n in fw.digests.indices) fw.digests[n] = null
            fw.next = 0
            fw.finishQueued = false
            lock.notifyAll()
        }
    }

    /** The receiver said a file was finished or not wanted: read the session again and take in what it says. */
    private fun resync() {
        val before = job.finished.size
        absorb(getState(files = true))
        // a file it calls finished that is still not accounted for would keep this send waiting for ever
        if (job.finished.size == before) throw SendException("${device.name} says a file is finished that it does not list. Send again.", "failed")
    }

    private fun readChunk(item: SendItem, offset: Long, length: Long): ByteArray {
        val stream = try {
            item.open()
        } catch (e: Exception) {
            job.fileFailure = e
            throw IOException("cannot open ${item.name}", e)
        }
        stream.use { input ->
            try {
                skipTo(input, offset)
                val buf = ByteArray(length.toInt())
                var got = 0
                while (got < buf.size) {
                    val n = input.read(buf, got, buf.size - got)
                    if (n < 0) throw SendException("${item.name} got shorter while it was being sent. Send it again." +
                        Errors.progressNote(job.saved.size, job.fileCount))
                    got += n
                }
                return buf
            } catch (e: SendException) {
                throw e
            } catch (e: IOException) {
                job.fileFailure = e
                throw e
            }
        }
    }

    private fun skipTo(input: InputStream, offset: Long) {
        if (offset == 0L) return
        if (input is FileInputStream) {
            input.channel.position(offset)
            return
        }
        var left = offset
        val sink = ByteArray(64 * 1024)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                val n = input.read(sink, 0, minOf(sink.size.toLong(), left).toInt())
                if (n < 0) throw SendException("File got shorter while it was being sent. Send it again.")
                left -= n
            }
        }
    }

    private fun countingBody(data: ByteArray, type: MediaType, counted: AtomicLong): RequestBody = object : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = data.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            var off = 0
            while (off < data.size) {
                if (job.cancelled || stopping) throw IOException("cancelled")
                val n = minOf(WRITE_SLICE, data.size - off)
                sink.write(data, off, n)
                off += n
                counted.addAndGet(n.toLong())
                job.addSent(n.toLong())
                tick()
            }
        }
    }

    /** Shows the new numbers to the screen, at most 5 times a second. */
    private fun tick() {
        val now = System.nanoTime()
        val last = updateAt.get()
        if (now - last > 200_000_000L && updateAt.compareAndSet(last, now)) {
            job.noteSent()
            job.update()
        }
    }

    // --- finishing a file

    private fun finishFile(fw: FileWork) {
        val item = fw.item
        job.current = item.name
        val digest = treeDigest(fw)
        val body = ProtocolJson.encodeToString(DoneRequest.serializer(), DoneRequest(V2.DIGEST_ALG, digest))
        val result = call(authed("/sessions/$sid/files/${fw.index}/done").post(body.toRequestBody(JSON)).build()) { r ->
            val text = r.body.string()
            when {
                r.code == 200 -> ProtocolJson.decodeFromString(DoneReply.serializer(), text)
                r.code == 409 && errorOf(text)?.error == "missing_chunks" -> {
                    // the receiver does not have all of them (it lost some): send those again
                    val missing = errorOf(text)?.missing.orEmpty()
                    synchronized(lock) {
                        for (range in missing) for (n in range[0] until range[1]) fw.have.remove(n)
                        fw.next = fw.have.firstMissing()
                        fw.finishQueued = false
                        lock.notifyAll()
                    }
                    null
                }
                r.code == 422 -> {
                    restartFile(fw)
                    null
                }
                else -> throw refusal(r, text)
            }
        }
        if (result == null) {
            synchronized(lock) { lock.notifyAll() }
            return
        }
        synchronized(lock) {
            job.verified++
            markDoneLocked(fw.index, result.name)
            lock.notifyAll()
        }
    }

    /** The tree digest of the whole file; chunks that went in an earlier try are hashed again from the file. */
    private fun treeDigest(fw: FileWork): String {
        if (fw.digests.all { it != null }) return V2.treeDigest(fw.digests.map { it!! }, chunkSize)
        val item = fw.item
        val stream = try {
            item.open()
        } catch (e: Exception) {
            job.fileFailure = e
            throw IOException("cannot open ${item.name}", e)
        }
        val (tree, all) = stream.use { V2.fileDigests(it, chunkSize.toInt()) }
        for (n in all.indices) if (n < fw.digests.size) fw.digests[n] = all[n]
        return tree
    }

    private fun markDone(i: Int, name: String) = synchronized(lock) { markDoneLocked(i, name) }

    private fun markDoneLocked(i: Int, name: String) {
        if (!job.finished.add(i)) return
        job.saved += name
        outstanding--
        val item = job.items[i]
        item.release()
        works[i]?.let { fw -> big.remove(fw) }
        job.noteSent()
        job.update()
    }

    // --- batches

    private fun sendBatch(unit: Piece.Batch) {
        val indexes = unit.indexes.filter { it !in job.finished }
        if (indexes.isEmpty()) return
        job.current = job.items[indexes.first()].name
        val pairs = indexes.map { i -> i.toString() to readSmall(job.items[i]) }
        val body = V2.encodeBatch(pairs)
        val request = authed("/sessions/$sid/batch").post(body.toRequestBody("application/x-kl-batch".toMediaType())).build()
        val reply = call(request) { r ->
            val text = r.body.string()
            if (r.code != 200) throw refusal(r, text)
            ProtocolJson.decodeFromString(BatchReply.serializer(), text)
        }
        var retry = false
        for (i in indexes) {
            val result = reply.results[i.toString()]
            when {
                result == null -> retry = true
                result.ok -> synchronized(lock) {
                    job.verified++
                    job.addSent(job.items[i].size)
                    markDoneLocked(i, result.name ?: job.items[i].name)
                    lock.notifyAll()
                }
                result.error == "no_space" -> throw SendException(Errors.refusal(507, ApiError("no_space"), device.name, job.saved.size, job.fileCount), "no_space")
                result.error == "wrong_size" -> throw SendException("${job.items[i].name} changed size while it was being sent. Send it again.", "changed")
                result.error == "finished" || result.error == "not_wanted" || result.error == "no_file" -> {
                    synchronized(lock) { leftOutNow(i, result.error!!); outstanding = job.items.indices.count { it !in job.finished }; lock.notifyAll() }
                }
                else -> { // digest_mismatch, failed, chunks_sent: once more on its own, then as a failure
                    if (batchRetries.merge(i, 1, Int::plus)!! > 2) throw SendException("${job.items[i].name} could not be saved on ${device.name} " +
                        "(${result.error}). Send it again.", result.error ?: "failed")
                    synchronized(lock) { small.addFirst(i); lock.notifyAll() }
                }
            }
        }
        if (retry) throw IOException("The answer to a batch did not list every file")
        tick()
    }

    private val batchRetries = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private fun readSmall(item: SendItem): ByteArray {
        val bytes = try {
            item.open().use { it.readBytes() }
        } catch (e: Exception) {
            job.fileFailure = e
            throw IOException("cannot open ${item.name}", e)
        }
        if (bytes.size.toLong() != item.size) {
            throw SendException("${item.name} changed size while it was being sent. Send it again.", "changed")
        }
        return bytes
    }

    // --- the end

    private fun finish() {
        // the receiver says `done` once every file is saved; give it a moment
        var state: SessionState? = null
        for (attempt in 0 until 20) {
            state = retrying { getState(files = false) }
            if (state.state == "done" || state.state in ENDED) break
            Thread.sleep(150)
        }
        if (state != null && state.state in ENDED) {
            val code = if (state.state == "declined") "declined" else if (state.state == "expired") "timeout" else "cancelled"
            throw SendException(Errors.refusal(410, ApiError(code), device.name, job.saved.size, job.fileCount), code)
        }
        job.current = ""
        job.noteSent()
        val tooBig = leftOut.count { "too_large" in it }
        if (tooBig > 0) {
            throw SendException("${if (tooBig == 1) "A file was" else "$tooBig files were"} too big for ${device.name}'s storage and not sent. " +
                job.doneMessage(), "too_large")
        }
        val extra = if (leftOut.isEmpty()) "" else ". ${leftOut.size} left out"
        when {
            job.items.isEmpty() -> job.update(SendJob.State.DONE, "Text sent")
            job.saved.isEmpty() && job.skipped > 0 -> job.update(SendJob.State.DONE, job.nothingNewMessage())
            else -> job.update(SendJob.State.DONE, job.doneMessage() + extra)
        }
    }

    companion object {
        private const val LONG_POLL_S = 25
        private const val START_STREAMS = 2
        private const val BATCH_BYTES = 8L * 1024 * 1024
        private const val WRITE_SLICE = 64 * 1024
        private val RAMP_NS = TimeUnit.SECONDS.toNanos(3)
        private val RETRY_DELAYS_S = longArrayOf(2, 4, 8, 15, 30)
        private val WINDOW_NS = TimeUnit.MINUTES.toNanos(3)
        private val ENDED = setOf("declined", "cancelled", "expired")
    }
}
