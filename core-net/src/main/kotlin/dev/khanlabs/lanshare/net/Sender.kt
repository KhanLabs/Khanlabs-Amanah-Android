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
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

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

/** One thing to send: its name on the other side (already Windows-safe) and its bytes. */
class SendItem(val name: String, val size: Long, val modified: Long = 0L, val open: () -> InputStream)

/** Who this device is, as it tells others in an offer. */
data class Me(val id: String, val name: String, val model: String, val app: String)

private val JSON = "application/json".toMediaType()

private fun url(host: String, port: Int, path: String): String {
    val h = if (':' in host) "[$host]" else host
    return "https://$h:$port${V1.API}$path"
}

private fun Response.bodyText(): String = body.string()

private fun errorOf(text: String): ApiError? = try {
    ProtocolJson.decodeFromString(ApiError.serializer(), text)
} catch (e: Exception) {
    null
}

/**
 * Asks a device who it is (GET /info). With [expectedId], a device with another certificate is refused with
 * [IdentityMismatchException]; without it, the ID comes from the certificate this connection used.
 */
fun fetchInfo(clients: Clients, host: String, port: Int = V1.DEFAULT_PORT, expectedId: String? = null,
              timeoutS: Long = 5): Device {
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
        return Device(id, info.name.ifEmpty { host }, info.model, listOf(host), port, info.app)
    }
}

/**
 * One send to one device (PROTOCOL.md section 4), the same steps as the Windows app's SendJob. [run] blocks, so
 * call it on a worker thread. [onUpdate] is called on that thread whenever something changes.
 */
class SendJob(
    private val clients: Clients,
    private val me: Me,
    val device: Device,
    val items: List<SendItem>,
    val text: String = "",
    /** "message" sends [text] as a chat text; the receiver may show it without asking. */
    private val kind: String = "",
    private val onUpdate: (SendJob) -> Unit = {},
) {
    enum class State { CONNECTING, WAITING, SENDING, DONE, DECLINED, FAILED, CANCELLED }

    val offerId: String = V2.b64url(ByteArray(16).also { SecureRandom().nextBytes(it) })
    @Volatile var state = State.CONNECTING
        private set
    @Volatile var message = ""
        private set
    val total: Long = items.sumOf { it.size }
    @Volatile var sent = 0L
        private set
    @Volatile var current = ""
        private set
    /** The names the receiver saved, in order. */
    val saved = ArrayList<String>()
    /** The address that answered. */
    @Volatile var host: String = device.hosts.firstOrNull() ?: ""
        private set
    /** What stopped the send, for the error log. The message the person sees is [message]. */
    @Volatile var failure: Throwable? = null
        private set
    @Volatile private var fileFailure: Throwable? = null
    @Volatile private var cancelled = false
    @Volatile private var call: Call? = null
    private val rate = Rate()

    val speed: Double get() = rate.speed()
    val etaSeconds: Long? get() = rate.eta(sent, total)

    private fun update(state: State? = null, message: String? = null) {
        if (state != null) this.state = state
        if (message != null) this.message = message
        try {
            onUpdate(this)
        } catch (e: Exception) {
            // a UI bug must not stop the send
        }
    }

    /** Stops the send: cuts the running request, then tells the receiver. */
    fun cancel() {
        cancelled = true
        call?.cancel()
        Thread {
            try {
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
            when {
                mismatch != null -> update(State.FAILED, mismatch.message)
                cancelled -> update(State.CANCELLED, "Cancelled")
                e is SendException -> update(State.FAILED, e.message)
                file != null -> update(State.FAILED, Errors.fileProblem(current.ifEmpty { "A file" }, file) +
                    Errors.progressNote(saved.size, items.size))
                else -> update(State.FAILED, Errors.connectionProblem(e, device.name, saved.size, items.size))
            }
        }
    }

    /** The first address that answers with the right certificate. */
    private fun pickHost() {
        var last: Exception? = null
        for (h in device.hosts) {
            try {
                fetchInfo(clients, h, device.port, device.id, timeoutS = if (device.hosts.size > 1) 4 else 10)
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
        val files = items.mapIndexed { i, it -> OfferFile(i.toString(), it.name, it.size) }
        val offer = OfferRequest(offerId, Sender(me.id, me.name, me.model, me.app), files, text, kind.ifEmpty { null })
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
        val tokens = try {
            ProtocolJson.decodeFromString(OfferAccepted.serializer(), body).files
        } catch (e: Exception) {
            throw SendException("${device.name} answered in a way Amanah doesn't understand. Check that both devices run the latest Amanah.")
        }
        if (files.isEmpty()) return update(State.DONE, "Text sent")
        update(State.SENDING, "Sending")
        for ((f, item) in files.zip(items)) {
            if (cancelled) return update(State.CANCELLED, "Cancelled")
            current = f.name
            saved += upload(f, item, tokens[f.id] ?: "")
        }
        current = ""
        update(State.DONE, "Sent ${files.size} file${if (files.size != 1) "s" else ""}")
    }

    private fun upload(f: OfferFile, item: SendItem, token: String): String {
        val u = url(host, device.port, "/upload").toHttpUrl().newBuilder()
            .addQueryParameter("offer", offerId).addQueryParameter("file", f.id).addQueryParameter("token", token)
            .build()
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
                            Errors.progressNote(saved.size, items.size))
                        sink.write(buf, 0, n)
                        left -= n
                        sent += n
                        val now = System.nanoTime()
                        if (now - last > 200_000_000L) {
                            last = now
                            rate.add(sent)
                            update()
                        }
                    }
                }
            }
        }
        val request = Request.Builder().url(u).put(body).build()
        clients.pinned(device.id).newCall(request).also { call = it }.execute().use { r ->
            val text = r.bodyText()
            if (r.code != 201) {
                throw SendException(Errors.refusal(r.code, errorOf(text), device.name, saved.size, items.size))
            }
            return try {
                ProtocolJson.decodeFromString(UploadReply.serializer(), text).name
            } catch (e: Exception) {
                f.name
            }
        }
    }

    private companion object {
        const val CHUNK = 256 * 1024
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
