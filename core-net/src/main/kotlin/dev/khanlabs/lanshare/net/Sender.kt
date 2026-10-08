package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.ApiError
import dev.khanlabs.lanshare.protocol.DeviceId
import dev.khanlabs.lanshare.protocol.Info
import dev.khanlabs.lanshare.protocol.OfferAccepted
import dev.khanlabs.lanshare.protocol.OfferFile
import dev.khanlabs.lanshare.protocol.OfferRequest
import dev.khanlabs.lanshare.protocol.ProtocolJson
import dev.khanlabs.lanshare.protocol.Sender
import dev.khanlabs.lanshare.protocol.V1
import dev.khanlabs.lanshare.protocol.V2
import dev.khanlabs.lanshare.protocol.CancelRequest
import dev.khanlabs.lanshare.protocol.UploadReply
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Another device, as discovery or "Add by IP" found it. [hosts] are its addresses, best first. */
data class Device(
    val id: String,
    val name: String,
    val model: String,
    val hosts: List<String>,
    val port: Int,
    val app: String = "",
) {
    val shortId: String get() = DeviceId.short(id)
}

/** The device answered, but not the way the protocol says. */
class SendException(message: String, val code: String = "") : IOException(message)

/** The receiver still has an earlier try of this file open; ask again in a moment (PROTOCOL.md 3.3). */
private class StillReceiving : IOException("the receiver is still busy with an earlier try")

/**
 * One thing to send: its name on the other side (already Windows-safe) and its bytes. [modified] is in milliseconds, 0 if unknown.
 * [preview] is the address (as text) the screen can draw a small picture from while the file is sent, or null.
 * [onRelease] closes whatever is held open for the file (see Items.fromUris); [release] runs it once, however often it is called.
 */
class SendItem(
    val name: String,
    val size: Long,
    val modified: Long = 0L,
    val preview: String? = null,
    private val onRelease: () -> Unit = {},
    val open: () -> InputStream,
) {
    private val released = AtomicBoolean(false)

    /** Nothing will read this file again. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            onRelease()
        } catch (e: Exception) {
            // already closed
        }
    }

    /**
     * [n] items for the same file, one for each send that reads it (a send to several devices). This item is released when
     * the last copy is, so the first send to finish can't close the file under the others.
     */
    fun copies(n: Int): List<SendItem> {
        require(n >= 1) { "at least one copy" }
        val left = java.util.concurrent.atomic.AtomicInteger(n)
        return List(n) { SendItem(name, size, modified, preview, onRelease = { if (left.decrementAndGet() == 0) release() }, open = open) }
    }
}

/** Who this device is, as it tells others in an offer. */
data class Me(val id: String, val name: String, val model: String, val app: String)

internal val JSON = "application/json".toMediaType()

internal fun url(host: String, port: Int, path: String): String {
    val h = if (':' in host) "[$host]" else host
    return "https://$h:$port${V1.API}$path"
}

private fun Response.bodyText(): String = body.string()

internal fun errorOf(text: String): ApiError? = try {
    ProtocolJson.decodeFromString(ApiError.serializer(), text)
} catch (e: Exception) {
    null
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

/**
 * Asks a device who it is (GET /info). With [expectedId], a device with another certificate is refused with
 * [IdentityMismatchException]; without it, the ID comes from the certificate this connection used.
 */
fun fetchInfo(clients: Clients, host: String, port: Int = V1.DEFAULT_PORT, expectedId: String? = null,
              timeoutS: Long = 5): Device = fetchInfoFull(clients, host, port, expectedId, timeoutS).first

/** Like [fetchInfo], and also the whole answer: its `protocols` say whether the device does v2 as well. */
fun fetchInfoFull(clients: Clients, host: String, port: Int = V1.DEFAULT_PORT, expectedId: String? = null,
                  timeoutS: Long = 5): Pair<Device, Info> {
    val client = clients.pinned(expectedId).newBuilder()
        .connectTimeout(timeoutS, TimeUnit.SECONDS).readTimeout(timeoutS, TimeUnit.SECONDS).build()
    client.newCall(Request.Builder().url(url(host, port, "/info")).build()).execute().use { r ->
        val id = r.header(Clients.PEER_ID) ?: throw SendException("That address doesn't answer like an Amanah device. Check the address.")
        val info = try {
            if (r.code == 200) ProtocolJson.decodeFromString(Info.serializer(), r.bodyText()) else null
        } catch (e: Exception) {
            null
        }
        if (info == null || info.id != id) throw SendException("That address doesn't answer like an Amanah device. Check the address.")
        return Device(id, info.name.ifEmpty { host }, info.model, listOf(host), port, info.app) to info
    }
}

/**
 * One send to one device (PROTOCOL.md section 4), the same steps as the Windows app's SendJob. [run] blocks, so
 * call it on a worker thread. [onUpdate] is called on that thread whenever something changes.
 *
 * Files the receiver already has are left out when the person keeps "skip" on there ([skipped]). A connection that
 * breaks in the middle of a file is picked up again inside the same offer (PROTOCOL.md 3.3, "Trying again after a
 * break"); the file starts over from its first byte.
 */
class SendJob(
    internal val clients: Clients,
    internal val me: Me,
    val device: Device,
    val items: List<SendItem>,
    val text: String = "",
    /** "message" sends [text] as a chat text; the receiver may show it without asking. */
    private val kind: String = "",
    /** This device's identity, to prove who is sending in protocol v2. Without it only v1 is used. */
    internal val identity: Identity? = null,
    /** False sends with v1 even to a device that does v2 (for tests, and for a device that misbehaves with v2). */
    private val allowV2: Boolean = true,
    private val onUpdate: (SendJob) -> Unit = {},
) {
    enum class State { CONNECTING, WAITING, SENDING, DONE, DECLINED, FAILED, CANCELLED }

    val offerId: String = V2.b64url(ByteArray(16).also { SecureRandom().nextBytes(it) })
    @Volatile var state = State.CONNECTING
        private set
    @Volatile var message = ""
        private set
    /** Bytes that will really be sent: files the receiver already has are taken off once it answers. */
    @Volatile var total: Long = items.sumOf { it.size }
        internal set
    @Volatile var sent = 0L
        internal set
    @Volatile var current = ""
        internal set
    /** The names the receiver saved, in order. v2 sends from several threads, so the list is synchronized. */
    val saved: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** How many files were left out because the receiver already has them, and their size. */
    @Volatile var skipped = 0
        internal set
    @Volatile var skippedBytes = 0L
        internal set
    /** How many sent files the receiver confirmed with the same SHA-256 as the bytes that were read here. */
    @Volatile var verified = 0
        internal set
    /** The connection broke and the send is waiting to try again (state stays SENDING). */
    @Volatile var reconnecting = false
        internal set
    /** When the receiver said yes, in milliseconds since 1970. 0 until then. */
    @Volatile var acceptedAt = 0L
        internal set
    /** How many times the connection broke and the send tried again. */
    @Volatile var resumes = 0
        internal set
    /** The fastest speed seen over a few seconds, in bytes per second. */
    @Volatile var peakSpeed = 0.0
        internal set
    /** The address that answered. */
    @Volatile var host: String = device.hosts.firstOrNull() ?: ""
        internal set
    /** What stopped the send, for the error log. The message the person sees is [message]. */
    @Volatile var failure: Throwable? = null
        internal set
    @Volatile internal var fileFailure: Throwable? = null
    @Volatile internal var cancelled = false
    @Volatile private var call: Call? = null
    /** The protocol versions the device said it speaks in its v1 info. */
    @Volatile internal var peerProtocols: List<Int> = emptyList()
    /** Every request a v2 send has running, so Cancel can cut them all. */
    internal val activeCalls: MutableSet<Call> = Collections.synchronizedSet(HashSet())
    @Volatile internal var v2: V2Sender? = null
    internal val rate = Rate()
    /** Positions in [items] that are done: sent, or left out as already there. */
    internal val finished: MutableSet<Int> = Collections.synchronizedSet(HashSet())
    /** Positions in [items] left out because the receiver already has them. */
    internal val alreadyThere: MutableSet<Int> = Collections.synchronizedSet(HashSet())
    /** Positions in [items] the receiver did not take (not chosen, too big for its disk, dropped). They are not sent again either. */
    internal val leftOut: MutableSet<Int> = Collections.synchronizedSet(HashSet())
    internal val retrySignal = Semaphore(0)

    val speed: Double get() = rate.speed()

    /** Feeds the speed meter after bytes went out, and keeps the best speed seen. */
    /** Adds to the bytes sent; v2 sends from several threads. [n] is negative to take back bytes of a try that failed. */
    @Synchronized
    internal fun addSent(n: Long) {
        sent += n
    }

    internal fun noteSent() {
        rate.add(sent)
        val now = rate.speed()
        if (now > peakSpeed) peakSpeed = now
    }
    val etaSeconds: Long? get() = rate.eta(sent, total)

    /** How many files are really being sent. */
    internal val fileCount: Int get() = items.size - skipped

    internal fun update(state: State? = null, message: String? = null) {
        if (state != null) this.state = state
        if (message != null) this.message = message
        try {
            onUpdate(this)
        } catch (e: Exception) {
            // a UI bug must not stop the send
        }
    }

    /** The items that did not get there (not sent, not already on the other side), for a new offer. */
    fun unfinished(): List<SendItem> = items.filterIndexed { i, _ -> i !in finished }

    /**
     * What became of each item, in the order of [items]: [SENT] (it went across), [ALREADY] (the receiver already had it,
     * so it was left out) or [UNSENT] (it did not get there, whatever the reason).
     */
    fun results(): List<String> = items.indices.map { i ->
        when {
            i in alreadyThere -> ALREADY
            i in leftOut -> UNSENT
            i in finished -> SENT
            else -> UNSENT
        }
    }

    /**
     * Gives back every file still held open. Call it when the send is dismissed, or ended with nothing to continue.
     * Not while "Send the rest" still needs [unfinished].
     */
    fun releaseItems() {
        for (item in items) item.release()
    }

    /** While waiting to try again: stop waiting and try now. */
    fun retryNow() {
        if (reconnecting) retrySignal.release()
    }

    /** Stops the send: cuts the running request, then tells the receiver. */
    fun cancel() {
        cancelled = true
        call?.cancel()
        synchronized(activeCalls) { activeCalls.toList() }.forEach { it.cancel() }
        retrySignal.release()
        Thread {
            try {
                val v2 = this.v2
                if (v2 != null) {
                    v2.cancelRemote()
                    return@Thread
                }
                val body = ProtocolJson.encodeToString(CancelRequest.serializer(), CancelRequest(offerId)).toRequestBody(JSON)
                clients.pinned(device.id).newCall(Request.Builder().url(url(host, device.port, "/cancel")).post(body).build())
                    .execute().close()
            } catch (e: Exception) {
                // the receiver will drop the offer on its own
            }
        }.apply { isDaemon = true }.start()
    }

    fun run() {
        try {
            send()
        } catch (e: Exception) {
            val mismatch = Clients.mismatch(e)
            val file = fileFailure
            if (!cancelled) failure = e
            reconnecting = false
            when {
                mismatch != null -> update(State.FAILED, mismatch.message)
                cancelled -> update(State.CANCELLED, "Cancelled")
                e is SendException -> update(State.FAILED, e.message)
                file != null -> update(State.FAILED, Errors.fileProblem(current.ifEmpty { "A file" }, file) +
                    Errors.progressNote(saved.size, fileCount))
                else -> update(State.FAILED, Errors.connectionProblem(e, device.name, saved.size, fileCount))
            }
        }
        reconnecting = false
    }

    /** The first address that answers with the right certificate. */
    internal fun pickHost() {
        var last: Exception? = null
        for (h in device.hosts) {
            try {
                val (_, info) = fetchInfoFull(clients, h, device.port, device.id, timeoutS = if (device.hosts.size > 1) 4 else 10)
                peerProtocols = info.protocols.orEmpty()
                host = h
                return
            } catch (e: Exception) {
                if (Clients.mismatch(e) != null) throw e
                last = e
            }
            if (cancelled) throw IOException("cancelled")
        }
        throw last ?: SendException("${device.name} has no address")
    }

    private fun send() {
        pickHost()
        if (cancelled) return update(State.CANCELLED, "Cancelled")
        // a device that does v2 is sent to with v2 (resume, checked files, fast small files); a text on its own stays on v1
        if (allowV2 && identity != null && items.isNotEmpty() && 2 in peerProtocols) {
            val sender = V2Sender(this, identity)
            v2 = sender
            if (sender.run()) return
            v2 = null // the device does not answer on the v2 API after all: carry on with v1
        }
        val files = items.mapIndexed { i, it -> OfferFile(i.toString(), it.name, it.size, (it.modified / 1000).takeIf { s -> s > 0 }) }
        val offer = OfferRequest(offerId, Sender(me.id, me.name, me.model, me.app), files, text, kind.ifEmpty { null },
            skipOk = true.takeIf { files.isNotEmpty() })
        update(State.WAITING, "Waiting for ${device.name} to accept")
        val client = clients.pinned(device.id).newBuilder()
            .readTimeout(V1.OFFER_TIMEOUT_S + 15, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url(host, device.port, "/offer"))
            .post(ProtocolJson.encodeToString(OfferRequest.serializer(), offer).toRequestBody(JSON)).build()
        val (code, body) = client.newCall(request).also { call = it }.execute().use { it.code to it.bodyText() }
        if (cancelled) return update(State.CANCELLED, "Cancelled")
        if (code != 200) {
            val err = errorOf(body)
            when (err?.error) {
                "declined" -> return update(State.DECLINED, Errors.refusal(code, err, device.name))
                "timeout" -> return update(State.DECLINED, Errors.refusal(code, err, device.name))
            }
            throw SendException(Errors.refusal(code, err, device.name, 0, files.size), err?.error ?: "")
        }
        val accepted = try {
            ProtocolJson.decodeFromString(OfferAccepted.serializer(), body)
        } catch (e: Exception) {
            throw SendException("${device.name} answered in a way Amanah doesn't understand. Check that both devices run the latest Amanah.")
        }
        val tokens = accepted.files
        acceptedAt = System.currentTimeMillis()
        val already = accepted.skip.orEmpty()
        files.forEachIndexed { i, f ->
            if (f.id in already) {
                finished += i
                alreadyThere += i
                skipped++
                skippedBytes += f.size
                items[i].release()
            }
        }
        total -= skippedBytes
        if (files.isEmpty()) return update(State.DONE, "Text sent")
        if (skipped == files.size) return update(State.DONE, nothingNewMessage())
        update(State.SENDING, "Sending")
        for ((i, f) in files.withIndex()) {
            if (i in finished) continue
            if (cancelled) return update(State.CANCELLED, "Cancelled")
            current = f.name
            saved += upload(f, items[i], tokens[f.id] ?: "")
            finished += i
            items[i].release() // a long send gives each file back as it goes
        }
        current = ""
        update(State.DONE, doneMessage())
    }

    internal fun doneMessage(): String {
        val n = saved.size
        if (skipped == 0) return "Sent $n file${if (n != 1) "s" else ""}"
        return "Sent $n new file${if (n != 1) "s" else ""}. ${if (skipped == 1) "1 was" else "$skipped were"} already on ${device.name}"
    }

    internal fun nothingNewMessage(): String =
        "Nothing new to send. ${if (skipped == 1) "That file was" else "All $skipped files were"} already on ${device.name}"

    /**
     * Sends one file, and sends it again from its first byte when the connection breaks (PROTOCOL.md 3.3). Waits 2, 4, 8,
     * 15, then 30 seconds between tries. Gives up when nothing has worked for 3 minutes; a try that got 1 MB further than
     * any earlier one starts the 3 minutes again. Only a plain broken connection is tried again: a refusal, a file that
     * can't be read, a different certificate or a Cancel is final.
     */
    private fun upload(f: OfferFile, item: SendItem, token: String): String {
        val before = sent
        var failingSince = 0L
        var best = 0L
        var step = 0
        var problem: Exception? = null // why the last try failed; null on the first pass
        while (true) {
            if (problem != null) {
                if (System.nanoTime() - failingSince > WINDOW_NS) {
                    if (problem is StillReceiving) {
                        throw SendException("${device.name} is still busy with an earlier try of ${f.name}. " +
                            "Wait a minute, then send it again." + Errors.progressNote(saved.size, fileCount), "already")
                    }
                    throw problem
                }
                pause(RETRY_DELAYS_S[minOf(step++, RETRY_DELAYS_S.size - 1)])
                if (cancelled) throw IOException("cancelled")
                try {
                    pickHost()
                } catch (e: Exception) {
                    if (cancelled || e !is IOException || Clients.mismatch(e) != null) throw e
                    problem = e
                    continue
                }
            }
            if (reconnecting) {
                reconnecting = false
                update(State.SENDING, "Sending")
            }
            try {
                if (problem != null) probe(f, item, token, before)?.let { return it }
                return uploadOnce(f, item, token, before)
            } catch (e: IOException) {
                if (cancelled || fileFailure != null || e is SendException || Clients.mismatch(e) != null) throw e
                val reached = sent - before
                if (failingSince == 0L || reached >= best + PROGRESS_BYTES) {
                    failingSince = System.nanoTime()
                    step = 0
                }
                best = maxOf(best, reached)
                sent = before
                rate.reset()
                problem = e
            }
        }
    }

    /**
     * Before a new try: asks the receiver what became of this file, with a PUT that has no body. A receiver checks the
     * token, then whether the file is done or still arriving, then whether the send was cancelled, and only then the
     * size, so the answer says what a real upload would hit: 409 done (the file arrived after all: returns the name it
     * was saved as, nothing is sent again), 409 receiving ([StillReceiving]: wait and ask again), 403 or 410 (the send is
     * over: [SendException]) and 400 wrong_size (the file can be sent again: returns null). Without it, a receiver that
     * cancelled would only show up as one more broken connection, and the sender would keep trying for 3 minutes. An
     * empty file needs no probe, as its real upload is just as small.
     */
    private fun probe(f: OfferFile, item: SendItem, token: String, before: Long): String? {
        if (item.size == 0L) return null
        val u = url(host, device.port, "/upload").toHttpUrl().newBuilder()
            .addQueryParameter("offer", offerId).addQueryParameter("file", f.id).addQueryParameter("token", token)
            .build()
        val request = Request.Builder().url(u).put(ByteArray(0).toRequestBody("application/octet-stream".toMediaType())).build()
        clients.pinned(device.id).newCall(request).also { call = it }.execute().use { r ->
            val err = errorOf(r.bodyText())
            if (r.code == 409 && err?.error == "already") {
                when (err.state) {
                    "done" -> {
                        sent = before + item.size
                        noteSent()
                        return err.name?.takeIf { it.isNotEmpty() } ?: f.name
                    }
                    "receiving" -> throw StillReceiving()
                }
            } else if (r.code == 403 || r.code == 410) {
                throw SendException(Errors.refusal(r.code, err, device.name, saved.size, fileCount), err?.error ?: "")
            }
            return null
        }
    }

    /** Waits [seconds], or less when the person taps "Try now" or cancels. */
    internal fun pause(seconds: Long) {
        retrySignal.drainPermits()
        reconnecting = true
        resumes++
        update(State.SENDING, "The connection to ${device.name} dropped. Trying again in $seconds seconds.")
        retrySignal.tryAcquire(seconds, TimeUnit.SECONDS)
    }

    private fun uploadOnce(f: OfferFile, item: SendItem, token: String, before: Long): String {
        val u = url(host, device.port, "/upload").toHttpUrl().newBuilder()
            .addQueryParameter("offer", offerId).addQueryParameter("file", f.id).addQueryParameter("token", token)
            .build()
        val digest = MessageDigest.getInstance("SHA-256")
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = item.size
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val stream = try {
                    item.open()
                } catch (e: Exception) {
                    fileFailure = e
                    throw IOException("cannot open ${f.name}", e)
                }
                stream.use { input ->
                    val buf = ByteArray(CHUNK)
                    var left = item.size
                    var last = 0L
                    while (left > 0) {
                        if (cancelled) throw IOException("cancelled")
                        val n = try {
                            input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        } catch (e: IOException) {
                            fileFailure = e
                            throw e
                        }
                        if (n < 0) throw SendException("${f.name} got shorter while it was being sent. Send it again." +
                            Errors.progressNote(saved.size, fileCount))
                        digest.update(buf, 0, n)
                        sink.write(buf, 0, n)
                        left -= n
                        sent += n
                        val now = System.nanoTime()
                        if (now - last > 200_000_000L) {
                            last = now
                            noteSent()
                            update()
                        }
                    }
                }
            }
        }
        val request = Request.Builder().url(u).put(body).build()
        clients.pinned(device.id).newCall(request).also { call = it }.execute().use { r ->
            val reply = r.bodyText()
            if (r.code == 409) {
                val err = errorOf(reply)
                if (err?.error == "already") {
                    when (err.state) {
                        // an earlier try got there after all: the file counts as sent (nothing to check it against)
                        "done" -> {
                            sent = before + item.size
                            noteSent()
                            return err.name?.takeIf { it.isNotEmpty() } ?: f.name
                        }
                        "receiving" -> throw StillReceiving()
                    }
                }
            }
            if (r.code != 201) {
                val err = errorOf(reply)
                throw SendException(Errors.refusal(r.code, err, device.name, saved.size, fileCount), err?.error ?: "")
            }
            val ok = try {
                ProtocolJson.decodeFromString(UploadReply.serializer(), reply)
            } catch (e: Exception) {
                null
            }
            val theirs = ok?.sha256
            if (theirs != null) {
                if (!theirs.equals(hex(digest.digest()), ignoreCase = true)) {
                    throw SendException("${f.name} arrived damaged on ${device.name}, and the damaged copy was saved there. " +
                        "Delete it there, then send ${f.name} again." + Errors.progressNote(saved.size, fileCount), "damaged")
                }
                verified++
            }
            return ok?.name ?: f.name
        }
    }

    companion object {
        /** The words [results] uses. */
        const val SENT = "done"
        const val ALREADY = "skipped"
        const val UNSENT = "unsent"

        private const val CHUNK = 256 * 1024
        private val RETRY_DELAYS_S = longArrayOf(2, 4, 8, 15, 30)
        private val WINDOW_NS = TimeUnit.MINUTES.toNanos(3)
        private const val PROGRESS_BYTES = 1024 * 1024L
    }
}

/** Speed over the last few seconds, and the time left, like the Windows app's Rate. */
class Rate(private val windowNs: Long = 5_000_000_000L) {
    private val points = ArrayDeque<Pair<Long, Long>>()

    @Synchronized
    fun add(done: Long, now: Long = System.nanoTime()) {
        points.addLast(now to done)
        while (points.size > 2 && now - points.first().first > windowNs) points.removeFirst()
    }

    /** Forgets what was measured, for a send that starts over. */
    @Synchronized
    fun reset() {
        points.clear()
    }

    /** Bytes per second, or 0 before there is enough to tell. */
    @Synchronized
    fun speed(): Double {
        if (points.size < 2) return 0.0
        val (t0, d0) = points.first()
        val (t1, d1) = points.last()
        if (t1 <= t0) return 0.0
        return (d1 - d0) * 1e9 / (t1 - t0)
    }

    fun eta(done: Long, total: Long): Long? {
        val s = speed()
        if (s <= 0 || total <= done) return null
        return ((total - done) / s).toLong()
    }
}
