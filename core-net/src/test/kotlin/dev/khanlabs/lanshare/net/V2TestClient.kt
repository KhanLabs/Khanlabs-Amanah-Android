package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.DoneRequest
import dev.khanlabs.lanshare.protocol.FileEntry
import dev.khanlabs.lanshare.protocol.InfoV2
import dev.khanlabs.lanshare.protocol.ManifestPage
import dev.khanlabs.lanshare.protocol.ProtocolJson
import dev.khanlabs.lanshare.protocol.SenderV2
import dev.khanlabs.lanshare.protocol.SessionCreate
import dev.khanlabs.lanshare.protocol.Summary
import dev.khanlabs.lanshare.protocol.V2
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** A small hand-written v2 client for tests of the receiver: one call per endpoint, raw answers. */
class V2TestClient(private val port: Int, private val receiverId: String, val me: Identity = Identity.create("v2-test-sender")) {
    private val http = Clients().pinnedV2(receiverId)

    class Resp(val code: Int, val body: String, val headers: Map<String, String>) {
        val json: JsonObject get() = ProtocolJson.parseToJsonElement(body).jsonObject
        operator fun get(key: String): JsonElement? = json[key]
        fun text(key: String): String? = json[key]?.jsonPrimitive?.content
        val error: String? get() = text("error")
        override fun toString() = "$code $body"
    }

    private fun send(request: Request): Resp = http.newCall(request).execute().use { r ->
        Resp(r.code, r.body.string(), r.headers.toMultimap().mapValues { it.value.first() })
    }

    private fun builder(path: String, token: String?) = Request.Builder().url("https://127.0.0.1:$port${V2.API}$path")
        .apply { if (token != null) header("Authorization", "Bearer $token") }

    private val json = "application/json".toMediaType()

    fun get(path: String, token: String? = null) = send(builder(path, token).get().build())
    fun delete(path: String, token: String? = null) = send(builder(path, token).delete().build())
    fun postJson(path: String, body: String, token: String? = null) = send(builder(path, token).post(body.toRequestBody(json)).build())
    fun postBytes(path: String, body: ByteArray, type: String, token: String? = null) =
        send(builder(path, token).post(body.toRequestBody(type.toMediaType())).build())

    fun putChunk(token: String, sid: String, fid: String, n: Long, data: ByteArray, digest: String = V2.chunkDigest(data),
                 contentLengthLies: Boolean = false): Resp {
        val body: RequestBody = data.toRequestBody("application/octet-stream".toMediaType())
        val b = builder("/sessions/$sid/files/$fid/chunks/$n", token).put(body)
        if (digest.isNotEmpty()) b.header("KL-Chunk-SHA256", digest)
        return send(b.build())
    }

    fun info(): InfoV2 = ProtocolJson.decodeFromString(InfoV2.serializer(), get("/info").body)

    /** A fresh nonce, from `GET /info`. */
    fun nonce(): String = info().nonce

    /** `POST /sessions` with a proof that is right unless a [tamper] changes it. */
    fun create(
        sid: String, files: List<FileEntry>, text: String? = null, more: Boolean = false, summary: Summary? = null,
        senderName: String = "Test sender", as_: Identity = me, tamper: (SessionCreate) -> SessionCreate = { it },
    ): Resp {
        val nonce = nonce()
        val made = DeviceProof.make(as_, receiverId, "session", nonce, sid)
        val sum = summary ?: Summary(files.size.toLong(), files.sumOf { it.size }, files.take(5).map { it.name.substringBefore('/') })
        val req = tamper(SessionCreate(sid, SenderV2(as_.id, senderName, "android", "test", 47820), made.cert, nonce, made.proof,
            sum, files, more, text))
        return postJson("/sessions", ProtocolJson.encodeToString(SessionCreate.serializer(), req))
    }

    fun manifest(token: String, sid: String, files: List<FileEntry>, more: Boolean) =
        postJson("/sessions/$sid/manifest", ProtocolJson.encodeToString(ManifestPage.serializer(), ManifestPage(files, more)), token)

    fun done(token: String, sid: String, fid: String, data: ByteArray, chunkSize: Int = V2.DEFAULT_CHUNK.toInt()) =
        postJson("/sessions/$sid/files/$fid/done",
            ProtocolJson.encodeToString(DoneRequest.serializer(), DoneRequest(V2.DIGEST_ALG, treeOf(data, chunkSize))), token)

    fun treeOf(data: ByteArray, chunkSize: Int = V2.DEFAULT_CHUNK.toInt()): String =
        V2.fileDigests(data.inputStream(), chunkSize).first

    /** Sends a whole file as chunks, in order. Returns the answer of `done`. */
    fun sendFile(token: String, sid: String, fid: String, data: ByteArray, chunkSize: Int = V2.DEFAULT_CHUNK.toInt()): Resp {
        val total = V2.chunkCount(data.size.toLong(), chunkSize.toLong())
        for (n in 0 until total) {
            val (offset, length) = V2.chunkSpan(n, data.size.toLong(), chunkSize.toLong())
            val r = putChunk(token, sid, fid, n, data.copyOfRange(offset.toInt(), (offset + length).toInt()))
            check(r.code == 201 || r.code == 200) { "chunk $n: $r" }
        }
        return done(token, sid, fid, data, chunkSize)
    }

    fun batch(token: String, sid: String, items: List<Pair<String, ByteArray>>) =
        postBytes("/sessions/$sid/batch", V2.encodeBatch(items), "application/x-kl-batch", token)

    fun state(token: String, sid: String, filesToo: Boolean = true) = get("/sessions/$sid" + if (filesToo) "" else "?files=none", token)

    companion object {
        fun entry(id: String, name: String, size: Long, mtime: Long? = null) = FileEntry(id, name, size, mtime)
        fun rangesOf(e: JsonElement?): List<List<Long>> =
            (e as? JsonArray)?.map { r -> r.jsonArray.map { it.jsonPrimitive.content.toLong() } } ?: emptyList()
        fun ids(e: JsonElement?): List<String> = (e as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
        val entries = ListSerializer(FileEntry.serializer())
    }
}
