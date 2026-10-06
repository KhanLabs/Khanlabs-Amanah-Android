package dev.khanlabs.lanshare.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Protocol v1 (docs/PROTOCOL.md). The numbers match the Windows app's transfer.py. */
object V1 {
    const val PROTOCOL = 1
    const val API = "/api/kl/v1"
    const val SERVICE_TYPE = "_klshare._tcp"
    const val DEFAULT_PORT = 47820
    const val PORT_TRIES = 10
    const val OFFER_TIMEOUT_S = 120L
    const val SESSION_IDLE_S = 3600L
    const val MAX_OFFER_BYTES = 4 * 1024 * 1024
    const val MAX_FILES = 10_000
    const val MAX_TEXT = 64 * 1024
    const val KIND_MESSAGE = "message"
    const val IDLE_TIMEOUT_S = 60
    const val MAX_CONN_PER_IP = 16
    const val MAX_CONN_TOTAL = 64
    const val MAX_PENDING_PER_IP = 2
    const val MAX_PENDING = 8
    val OFFER_ID = Regex("[A-Za-z0-9_-]{16,64}")
}

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class Info(
    val protocol: Int,
    val id: String,
    val name: String,
    val model: String,
    val app: String = "",
    val receiving: Boolean = true,
    val protocols: List<Int>? = null,
)

@Serializable
data class Sender(val id: String = "", val name: String = "", val model: String = "", val app: String = "")

@Serializable
data class OfferFile(val id: String, val name: String, val size: Long)

@Serializable
data class OfferRequest(
    val offer: String,
    val sender: Sender,
    val files: List<OfferFile> = emptyList(),
    val text: String? = null,
    /** "message" for a chat text (PROTOCOL.md 3.2); left out otherwise. */
    val kind: String? = null,
)

@Serializable
data class OfferAccepted(val offer: String, val files: Map<String, String>)

@Serializable
data class UploadReply(val ok: Boolean, val name: String)

@Serializable
data class CancelRequest(val offer: String)

@Serializable
data class OkReply(val ok: Boolean = true)

@Serializable
data class ApiError(val error: String, val message: String = "")

/** An error reply: HTTP status plus the fixed code from the protocol. */
class ProtocolException(val status: Int, val code: String, message: String) : Exception(message) {
    val body: ApiError get() = ApiError(code, message ?: code)
}

/** A checked incoming offer. Names are already cleaned with [Names.cleanRelPath]. */
data class CheckedOffer(
    val id: String,
    val sender: Sender,
    val files: List<OfferFile>,
    val text: String,
    /** "message" only for text without files that asked for it, else empty. */
    val kind: String = "",
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

object OfferCheck {
    /** The request body as a JSON object, or 400 bad_json. */
    fun parseObject(body: ByteArray): JsonObject {
        val element = try {
            ProtocolJson.parseToJsonElement(body.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ProtocolException(400, "bad_json", "The body must be JSON")
        }
        return element as? JsonObject ?: throw ProtocolException(400, "bad_json", "The body must be a JSON object")
    }

    /** The rules of PROTOCOL.md 3.2, checked in the same order as the Windows app, so both give the same code. */
    fun check(data: JsonObject): CheckedOffer {
        val offerId = (data["offer"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (offerId == null || !V1.OFFER_ID.matches(offerId)) {
            throw ProtocolException(400, "bad_offer", "offer must be a random id of 16-64 letters, digits, - or _")
        }
        val s = data["sender"] as? JsonObject ?: JsonObject(emptyMap())
        fun field(k: String) = s[k]?.let { pyStr(it) }?.take(128) ?: ""
        val sender = Sender(field("id"), field("name"), field("model"), field("app"))

        val text = when (val t = data["text"]) {
            null, JsonNull -> ""
            is JsonPrimitive -> if (t.isString) t.content else if (falsy(t)) "" else null
            else -> if (falsy(t)) "" else null
        }
        if (text == null || text.toByteArray(Charsets.UTF_8).size > V1.MAX_TEXT) {
            throw ProtocolException(400, "bad_text", "text must be a string of at most 64 KB")
        }
        val rawFiles = when (val f = data["files"]) {
            null, JsonNull -> JsonArray(emptyList())
            is JsonArray -> f
            else -> if (falsy(f)) JsonArray(emptyList()) else null
        }
        if (rawFiles == null || rawFiles.size > V1.MAX_FILES) {
            throw ProtocolException(400, "bad_files", "files must be a list of at most ${V1.MAX_FILES}")
        }
        val files = ArrayList<OfferFile>(rawFiles.size)
        val seen = HashSet<String>()
        for (f in rawFiles) {
            if (f !is JsonObject) throw ProtocolException(400, "bad_files", "each file must be an object")
            val fid = f["id"]?.let { pyStr(it) } ?: ""
            val name = (f["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { Names.cleanRelPath(it) }
            val size = (f["size"] as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.longOrNull
            if (fid.isEmpty() || fid in seen || fid.length > 64 || name == null || size == null || size < 0) {
                throw ProtocolException(400, "bad_files", "each file needs a unique id, a valid relative name and a size")
            }
            seen += fid
            files += OfferFile(fid, name, size)
        }
        if (files.isEmpty() && text.isEmpty()) throw ProtocolException(400, "empty", "Send at least one file or some text")
        val asked = (data["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content == V1.KIND_MESSAGE
        return CheckedOffer(offerId, sender, files, text, if (asked && text.isNotEmpty() && files.isEmpty()) V1.KIND_MESSAGE else "")
    }

    /** Python's str() of a JSON value, for the few fields the Windows app converts that way. */
    private fun pyStr(e: JsonElement): String = when (e) {
        is JsonPrimitive -> when {
            e.isString -> e.content
            e is JsonNull -> "None"
            e.booleanOrNull != null -> if (e.booleanOrNull == true) "True" else "False"
            else -> e.content
        }
        else -> e.toString()
    }

    private fun falsy(e: JsonElement): Boolean = when (e) {
        is JsonNull -> true
        is JsonPrimitive -> if (e.isString) e.content.isEmpty() else e.booleanOrNull == false || e.content == "0" || e.content == "0.0"
        is JsonArray -> e.isEmpty()
        is JsonObject -> e.isEmpty()
    }
}
