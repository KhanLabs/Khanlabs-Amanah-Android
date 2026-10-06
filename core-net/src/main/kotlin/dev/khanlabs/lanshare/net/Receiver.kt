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

class IncomingFile(val id: String, val name: String, val size: Long) {
    @Volatile var token: String? = null
    @Volatile var state: String = "waiting" // waiting, receiving, done, failed
    @Volatile var received: Long = 0
    /** The relative name it was saved as, once done. */
    @Volatile var savedName: String? = null
}

class Offer(val id: String, val sender: Sender, val ip: String, val files: List<IncomingFile>, val text: String) {
    @Volatile var state: String = "waiting" // waiting, accepted, declined, expired, cancelled, done
        internal set
    @Volatile internal var lastSeen: Long = System.nanoTime()
    internal val answered = CountDownLatch(1)
    internal var store: ReceiveStore? = null
    val created: Long = System.currentTimeMillis()

    val total: Long get() = files.sumOf { it.size }
    val received: Long get() = files.sumOf { it.received }
    val senderName: String get() = sender.name.ifEmpty { ip }.take(64)
}

/** What the receiver tells the app. Called on server threads; must not block for long. */
interface ReceiverEvents {
    /** Ask the person, then call [Receiver.answer]. */
    fun onOffer(offer: Offer)
    fun onFile(offer: Offer, file: IncomingFile) {}
    fun onProgress(offer: Offer, file: IncomingFile) {}
    fun onFinished(offer: Offer) {}
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

    /** Called by the app when the person answers. Accepting hands out one upload token per file. */
    fun answer(offerId: String, accept: Boolean): Boolean {
        val offer = get(offerId) ?: return false
        synchronized(offer) {
            if (offer.state != "waiting") return false
            if (accept) {
                offer.store = store()
                for (f in offer.files) f.token = V2.b64url(ByteArray(24).also { random.nextBytes(it) })
                offer.state = "accepted"
                offer.lastSeen = System.nanoTime()
            } else {
                offer.state = "declined"
            }
        }
        if (!accept) notify { onFinished(offer) }
        else if (offer.files.isEmpty()) finish(offer, "done", wake = false) // text only: nothing to upload
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
            checked.files.map { IncomingFile(it.id, it.name, it.size) }, checked.text)
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
        notify { onOffer(offer) }
        val answered = offer.answered.await(offerTimeoutMs, TimeUnit.MILLISECONDS)
        if (!answered) {
            finish(offer, "expired")
            return x.fail(408, "timeout", "Nobody answered in time")
        }
        if (offer.state != "accepted" && offer.state != "done") {
            return x.fail(403, if (offer.state == "cancelled") "cancelled" else "declined", "The offer was declined")
        }
        x.sendJson(200, OfferAccepted.serializer(), OfferAccepted(offer.id, offer.files.associate { it.id to (it.token ?: "") }))
    }

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
        if (f.state == "receiving" || f.state == "done") return x.fail(409, "already", "That file is already being received or done")
        if (offer.state != "accepted") return x.fail(410, "cancelled", "This send was cancelled")
        val length = x.contentLength ?: return x.fail(411, "length_required", "Content-Length is required")
        if (length != f.size) return x.fail(400, "wrong_size", "Content-Length must match the size in the offer")

        val upload = try {
            offer.store!!.open(f.name, f.size)
        } catch (e: Exception) {
            return x.fail(409, "folder", "Couldn't create the folder for that file")
        }
        f.state = "receiving"
        f.received = 0
        x.bodyRead = true // from here on the body is streamed, or too far gone to drain
        notify { onFile(offer, f) }
        var last = 0L
        try {
            upload.output.use { out ->
                val buf = ByteArray(CHUNK)
                while (f.received < length) {
                    if (offer.state != "accepted") throw CancelledException()
                    val n = x.body.read(buf, 0, minOf(buf.size.toLong(), length - f.received).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    f.received += n
                    val now = System.nanoTime()
                    offer.lastSeen = now
                    if (now - last > 250_000_000L) {
                        last = now
                        notify { onProgress(offer, f) }
                    }
                }
            }
            if (f.received < length) throw EOFException("connection dropped")
            f.savedName = upload.commit()
        } catch (e: Exception) {
            upload.abort()
            f.state = "failed"
            notify { onFile(offer, f) }
            if (e is SocketTimeoutException) return x.hangUp() // the sender went silent; it can send this file again
            if (offer.state != "accepted") return x.fail(410, "cancelled", "This send was cancelled")
            if (e is IOException && isDiskFull(e)) return x.fail(507, "no_space", "This device ran out of space")
            return x.fail(500, "failed", "Upload failed")
        }
        f.state = "done"
        notify { onFile(offer, f) }
        if (offer.files.all { it.state == "done" }) finish(offer, "done")
        x.sendJson(201, UploadReply.serializer(), UploadReply(true, f.savedName ?: f.name))
    }

    private class CancelledException : IOException("cancelled")

    private companion object {
        const val CHUNK = 256 * 1024

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
