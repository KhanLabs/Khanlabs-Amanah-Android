package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.Info
import dev.khanlabs.lanshare.protocol.OfferAccepted
import dev.khanlabs.lanshare.protocol.OfferCheck
import dev.khanlabs.lanshare.protocol.OkReply
import dev.khanlabs.lanshare.protocol.ProtocolException
import dev.khanlabs.lanshare.protocol.Sender
import dev.khanlabs.lanshare.protocol.UploadReply
import dev.khanlabs.lanshare.protocol.V1
import dev.khanlabs.lanshare.protocol.V2
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class IncomingFile(val id: String, val name: String, val size: Long, val mtime: Long? = null) {
    @Volatile var token: String? = null
    @Volatile var state: String = "waiting" // waiting, receiving, done, failed
    @Volatile var received: Long = 0
    /** The relative name it was saved as, once done. */
    @Volatile var savedName: String? = null
    /** SHA-256 of the bytes written, once done. */
    @Volatile var sha256: String? = null
    /** A file with this name and size (and date, when both sides know it) was already in the receive folder. */
    @Volatile var alreadyHere = false
    /** The name that file has in the receive folder. */
    @Volatile var hereAs: String? = null
    /** Left out of this send because it is already here and the person kept "skip" on. */
    @Volatile var skipped = false

    /** Counts uploads of this file: a new upload that takes over from a stalled one bumps it. Guarded by `synchronized(this)`. */
    internal var generation = 0
    @Volatile internal var lastBytesAt: Long = System.nanoTime()
}

class Offer(
    val id: String,
    val sender: Sender,
    val ip: String,
    val files: List<IncomingFile>,
    val text: String,
    /** "message" for a chat text, else empty. */
    val kind: String = "",
    /** The sender understands the `skip` list in the answer. */
    val skipOk: Boolean = false,
) {
    @Volatile var state: String = "waiting" // waiting, accepted, declined, expired, cancelled, done
        internal set
    @Volatile internal var lastSeen: Long = System.nanoTime()
    internal val answered = CountDownLatch(1)
    internal var store: ReceiveStore? = null
    val created: Long = System.currentTimeMillis()

    /** What will really be sent: files left out as "already here" don't count. */
    val total: Long get() = files.sumOf { if (it.skipped) 0L else it.size }
    val received: Long get() = files.sumOf { if (it.skipped) 0L else it.received }
    val senderName: String get() = sender.name.ifEmpty { ip }.take(64)

    /** Files found already in the receive folder when the offer came in (only filled when the sender sent `skip_ok`). */
    val already: List<IncomingFile> get() = files.filter { it.alreadyHere }
    val alreadyBytes: Long get() = already.sumOf { it.size }
}

/** What the receiver tells the app. Called on server threads; must not block for long. */
interface ReceiverEvents {
    /** Ask the person, then call [Receiver.answer]. */
    fun onOffer(offer: Offer)
    fun onFile(offer: Offer, file: IncomingFile) {}
    fun onProgress(offer: Offer, file: IncomingFile) {}
    fun onFinished(offer: Offer) {}

    /** A chat text from a device the person already talks to. It was delivered without a prompt. */
    fun onMessage(offer: Offer) {}
}

data class ReceiverSettings(val name: String, val receiving: Boolean)

/**
 * The protocol v1 API other devices send to (PROTOCOL.md section 3), with the same rules, codes and limits as the
 * Windows app's transfer.Receiver.
 */
class Receiver(
    private val identity: Identity,
    private val settings: () -> ReceiverSettings,
    /** Asked when an offer is accepted, so a changed folder applies to the next send. */
    private val store: () -> ReceiveStore,
    private val events: ReceiverEvents,
    private val app: String,
    private val model: String = "android",
    port: Int = V1.DEFAULT_PORT,
    host: InetAddress? = null,
    idleTimeoutMs: Int = V1.IDLE_TIMEOUT_S * 1000,
    peekPlainHttp: Boolean = true,
    peeker: Peeker? = null,
    private val offerTimeoutMs: Long = V1.OFFER_TIMEOUT_S * 1000,
    /** May this chat text skip the prompt? Only for a known device at an address it announced (see [onMessage]). */
    private val messageTrusted: (Offer) -> Boolean = { false },
) {
    private val offers = LinkedHashMap<String, Offer>()
    private val lock = Any()
    private val random = SecureRandom()
    private val server = HttpsServer(identity.serverContext(), ::handle, host, port, V1.PORT_TRIES, idleTimeoutMs,
        V1.MAX_CONN_PER_IP, V1.MAX_CONN_TOTAL, peekPlainHttp, peeker)
    val port: Int get() = server.port
    @Volatile private var sweeper: Thread? = null

    fun start() {
        server.start()
        sweeper = Thread(::sweep, "klshare-sweep").apply { isDaemon = true; start() }
    }

    fun stop() {
        sweeper?.interrupt()
        sweeper = null
        val all = synchronized(lock) { offers.values.toList() }
        for (o in all) if (o.state == "waiting" || o.state == "accepted") finish(o, "cancelled")
        server.stop()
    }

    fun info() = settings().let {
        Info(V1.PROTOCOL, identity.id, it.name, model, app, it.receiving)
    }

    fun get(offerId: String): Offer? = synchronized(lock) { offers[offerId] }

    /**
     * Called by the app when the person answers. Accepting hands out one upload token per file. With [skip], files
     * found already here ([Offer.already]) get no token and are reported back as done, if the sender asked for that.
     */
    fun answer(offerId: String, accept: Boolean, skip: Boolean = false): Boolean {
        val offer = get(offerId) ?: return false
        synchronized(offer) {
            if (offer.state != "waiting") return false
            if (accept) {
                offer.store = store()
                for (f in offer.files) {
                    if (skip && offer.skipOk && f.alreadyHere) {
                        f.skipped = true
                        f.state = "done"
                    } else {
                        f.token = V2.b64url(ByteArray(24).also { random.nextBytes(it) })
                    }
                }
                offer.state = "accepted"
                offer.lastSeen = System.nanoTime()
            } else {
                offer.state = "declined"
            }
        }
        if (!accept) notify { onFinished(offer) }
        else if (offer.files.all { it.skipped }) finish(offer, "done", wake = false) // text only, or all already here: nothing to upload
        offer.answered.countDown()
        return true
    }

    /** The person (or the sender) stopped the send. Running and later uploads get 410. */
    fun cancel(offerId: String) {
        val offer = get(offerId) ?: return
        if (offer.state == "waiting" || offer.state == "accepted") finish(offer, "cancelled")
    }

    private fun finish(offer: Offer, state: String, wake: Boolean = true) {
        synchronized(offer) {
            if (offer.state == state) return
            offer.state = state
            offer.lastSeen = System.nanoTime()
        }
        if (wake) offer.answered.countDown()
        notify { onFinished(offer) }
    }

    /**
     * A chat text from a device the person already talks to is shown at once; anything else waits for an answer like
     * any other offer. The sender's ID in an offer proves nothing (PROTOCOL.md section 7), so [messageTrusted] must also
     * check that the text comes from an address that device announced.
     */
    private fun deliverMessage(offer: Offer): Boolean {
        val trusted = try {
            messageTrusted(offer)
        } catch (e: Exception) {
            false
        }
        if (!trusted) return false
        synchronized(offer) {
            offer.state = "done"
            offer.lastSeen = System.nanoTime()
        }
        offer.answered.countDown()
        notify { onMessage(offer) }
        return true
    }

    private fun add(offer: Offer): String = synchronized(lock) {
        if (offer.id in offers) return "duplicate"
        val waiting = offers.values.filter { it.state == "waiting" }
        if (waiting.size >= V1.MAX_PENDING || waiting.count { it.ip == offer.ip } >= V1.MAX_PENDING_PER_IP) return "busy"
        offers[offer.id] = offer
        "ok"
    }

    private inline fun notify(block: ReceiverEvents.() -> Unit) {
        try {
            events.block()
        } catch (e: Exception) {
            // a UI bug must never take a server thread down
        }
    }

    /**
     * Marks the files the receive folder already has: the same relative name and size, and when both sides know the
     * modified time, within 2 seconds of each other (PROTOCOL.md 3.2). A failing store just means nothing is marked.
     */
    private fun markAlreadyHere(offer: Offer) {
        val s = try {
            store()
        } catch (e: Exception) {
            return
        }
        for (f in offer.files) {
            val found = try {
                s.existing(f.name)
            } catch (e: Exception) {
                null
            } ?: continue
            if (found.size != f.size) continue
            val theirs = f.mtime
            val ours = found.mtime
            if (theirs != null && ours != null && abs(theirs - ours) > MTIME_SLACK_S) continue
            f.hereAs = found.savedName
            f.alreadyHere = true
        }
    }

    private fun sweep() {
        while (sweeper != null) {
            try {
                Thread.sleep(30_000)
            } catch (e: InterruptedException) {
                return
            }
            val now = System.nanoTime()
            val all = synchronized(lock) { offers.values.toList() }
            for (o in all) {
                val idle = TimeUnit.NANOSECONDS.toSeconds(now - o.lastSeen)
                if (o.state == "accepted" && idle > V1.SESSION_IDLE_S) {
                    finish(o, "cancelled") // the sender went quiet
                } else if (o.state != "waiting" && o.state != "accepted" && idle > 300) {
                    synchronized(lock) { offers.remove(o.id) } // kept a while, so late uploads get a clear 410
                }
            }
        }
    }

    // --- requests

    private fun handle(x: Exchange) {
        if (!HttpsServer.hostAllowed(x.headers["host"])) return x.fail(421, "wrong_host", "Use the device's IP address")
        when (x.method to x.path) {
            "GET" to "${V1.API}/info" -> x.sendJson(200, Info.serializer(), info())
            "POST" to "${V1.API}/offer" -> offer(x)
            "POST" to "${V1.API}/cancel" -> {
                val data = readJson(x) ?: return
                cancel(data["offer"]?.let { pyStr(it) } ?: "")
                x.sendJson(200, OkReply.serializer(), OkReply())
            }
            "PUT" to "${V1.API}/upload" -> upload(x)
            else -> if (x.method in setOf("GET", "POST", "PUT", "HEAD")) {
                x.fail(404, "not_found", "Unknown endpoint")
            } else {
                x.fail(501, "not_implemented", "Unsupported method")
            }
        }
    }

    private fun readJson(x: Exchange): JsonObject? {
        val length = x.contentLength ?: run {
            x.fail(411, "length_required", "Content-Length is required")
            return null
        }
        if (length > V1.MAX_OFFER_BYTES) {
            x.fail(413, "too_large", "Request too large")
            return null
        }
        x.bodyRead = true
        val body = x.body.readBytes()
        return try {
            OfferCheck.parseObject(body)
        } catch (e: ProtocolException) {
            x.fail(e.status, e.code, e.message ?: e.code)
            null
        }
    }

    private fun offer(x: Exchange) {
        val data = readJson(x) ?: return
        if (!settings().receiving) return x.fail(403, "not_receiving", "This device isn't accepting files right now")
        val checked = try {
            OfferCheck.check(data)
        } catch (e: ProtocolException) {
            return x.fail(e.status, e.code, e.message ?: e.code)
        }
        val offer = Offer(checked.id, checked.sender, x.remoteIp,
            checked.files.map { IncomingFile(it.id, it.name, it.size, it.mtime) }, checked.text, checked.kind, checked.skipOk)
        when (add(offer)) {
            "duplicate" -> return x.fail(409, "duplicate", "That offer id is already in use")
            "busy" -> return x.fail(429, "busy",
                "This device already has sends waiting for an answer. Try again after it answers.")
        }
        val free = try {
            store().freeSpace()
        } catch (e: Exception) {
            0L
        }
        if (offer.total > free) {
            finish(offer, "declined")
            return x.fail(507, "no_space", "Not enough free space on this device")
        }
        if (offer.kind == V1.KIND_MESSAGE && deliverMessage(offer)) {
            return x.sendJson(200, OfferAccepted.serializer(), OfferAccepted(offer.id, emptyMap()))
        }
        if (offer.skipOk && offer.files.isNotEmpty()) markAlreadyHere(offer)
        notify { onOffer(offer) }
        val answered = offer.answered.await(offerTimeoutMs, TimeUnit.MILLISECONDS)
        if (!answered) {
            finish(offer, "expired")
            return x.fail(408, "timeout", "Nobody answered in time")
        }
        if (offer.state != "accepted" && offer.state != "done") {
            return x.fail(403, if (offer.state == "cancelled") "cancelled" else "declined", "The offer was declined")
        }
        val tokens = offer.files.filter { !it.skipped }.associate { it.id to (it.token ?: "") }
        val skip = offer.files.filter { it.skipped }.associate { it.id to (it.hereAs ?: it.name) }
        x.sendJson(200, OfferAccepted.serializer(), OfferAccepted(offer.id, tokens, skip.takeIf { it.isNotEmpty() }))
    }

    /** "done", "receiving" (bytes arrived in the last 3 seconds) or null when a new upload may start or take over. */
    private fun busyState(f: IncomingFile): String? = when {
        f.state == "done" -> "done"
        f.state == "receiving" && System.nanoTime() - f.lastBytesAt < TAKEOVER_NS -> "receiving"
        else -> null
    }

    /**
     * A sender that tries a big file again after it had already arrived is still sending it. Read all of it before
     * answering "done", or the connection resets and the sender never sees the answer.
     */
    private fun refuseBusy(x: Exchange, f: IncomingFile, state: String) =
        x.fail(409, "already", "That file is already being received or done", state, if (state == "done") f.savedName else null,
            drainLimit = if (state == "done") Long.MAX_VALUE else 1024 * 1024)

    private fun upload(x: Exchange) {
        val offer = get(x.query["offer"] ?: "")
        val f = offer?.files?.firstOrNull { it.id == x.query["file"] }
        val token = f?.token
        if (f == null || token == null ||
            !MessageDigest.isEqual(token.toByteArray(), (x.query["token"] ?: "").toByteArray())
        ) {
            return x.fail(403, "bad_token", "Unknown offer, file or token")
        }
        offer!!
        busyState(f)?.let { return refuseBusy(x, f, it) }
        if (offer.state != "accepted") return x.fail(410, "cancelled", "This send was cancelled")
        val length = x.contentLength ?: return x.fail(411, "length_required", "Content-Length is required")
        if (length != f.size) return x.fail(400, "wrong_size", "Content-Length must match the size in the offer")

        val upload = try {
            offer.store!!.open(f.name, f.size)
        } catch (e: Exception) {
            return x.fail(409, "folder", "Couldn't create the folder for that file")
        }
        // Claim the file. A new upload takes over from one that went quiet (a sender that came back after a dropped
        // connection); the old upload sees the changed generation and stops without touching anything shared.
        val gen = synchronized(f) {
            if (busyState(f) != null) null
            else {
                f.generation++
                f.state = "receiving"
                f.received = 0
                f.lastBytesAt = System.nanoTime()
                f.generation
            }
        }
        if (gen == null) {
            upload.abort()
            return refuseBusy(x, f, busyState(f) ?: "receiving")
        }
        x.bodyRead = true // from here on the body is streamed, or too far gone to drain
        notify { onFile(offer, f) }
        var last = 0L
        var got = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val saved: String
        try {
            upload.output.use { out ->
                val buf = ByteArray(CHUNK)
                while (got < length) {
                    if (offer.state != "accepted") throw CancelledException()
                    val n = x.body.read(buf, 0, minOf(buf.size.toLong(), length - got).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    got += n
                    val now = System.nanoTime()
                    synchronized(f) {
                        if (f.generation != gen) throw SupersededException()
                        f.received = got
                        f.lastBytesAt = now
                    }
                    offer.lastSeen = now
                    if (now - last > 250_000_000L) {
                        last = now
                        notify { onProgress(offer, f) }
                    }
                }
            }
            if (got < length) throw EOFException("connection dropped")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            saved = synchronized(f) {
                if (f.generation != gen) throw SupersededException()
                upload.commit().also {
                    f.savedName = it
                    f.sha256 = hash
                    f.state = "done"
                }
            }
        } catch (e: Exception) {
            upload.abort()
            val mine = synchronized(f) {
                if (f.generation == gen) {
                    f.state = "failed"
                    true
                } else false
            }
            if (!mine) return x.hangUp() // a newer upload owns this file now
            notify { onFile(offer, f) }
            if (e is SocketTimeoutException) return x.hangUp() // the sender went silent; it can send this file again
            if (offer.state != "accepted") return x.fail(410, "cancelled", "This send was cancelled")
            if (e is IOException && isDiskFull(e)) return x.fail(507, "no_space", "This device ran out of space")
            return x.fail(500, "failed", "Upload failed")
        }
        try {
            offer.store?.remember(f.name, saved, f.size, f.mtime)
        } catch (e: Exception) {
            // the date and the index are extras; the file is saved
        }
        notify { onFile(offer, f) }
        if (offer.files.all { it.state == "done" }) finish(offer, "done")
        x.sendJson(201, UploadReply.serializer(), UploadReply(true, saved, f.sha256))
    }

    private class CancelledException : IOException("cancelled")

    /** A newer upload of the same file took over while this one was running. */
    private class SupersededException : IOException("superseded")

    private companion object {
        const val CHUNK = 256 * 1024

        /** A second upload of a file may take over when nothing arrived on the first for this long. */
        const val TAKEOVER_NS = 3_000_000_000L

        /** Two modified times this close (seconds) count as the same, as file systems round them differently. */
        const val MTIME_SLACK_S = 2L

        /** Python's str() of a JSON value, as the Windows app reads the cancel request. */
        fun pyStr(e: kotlinx.serialization.json.JsonElement): String = when {
            e is JsonNull -> "None"
            e is JsonPrimitive && e.isString -> e.content
            e is JsonPrimitive && e.content == "true" -> "True"
            e is JsonPrimitive && e.content == "false" -> "False"
            else -> e.toString()
        }
    }
}
