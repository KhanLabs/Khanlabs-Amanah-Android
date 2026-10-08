package dev.khanlabs.lanshare.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.BitSet

/**
 * The messages of protocol v2 (PROTOCOL-v2.md), named as in docs/v2/schema.json. Unknown fields are ignored
 * ([ProtocolJson]), so later versions can add some.
 */

@Serializable
data class V2Limits(
    @SerialName("max_streams") val maxStreams: Int,
    @SerialName("chunk_size") val chunkSize: Long,
    @SerialName("batch_max_file") val batchMaxFile: Long,
    @SerialName("batch_max_bytes") val batchMaxBytes: Long,
    @SerialName("batch_max_files") val batchMaxFiles: Int,
    @SerialName("manifest_page") val manifestPage: Int,
)

@Serializable
data class InfoV2(
    val protocol: Int = V2.PROTOCOL,
    val protocols: List<Int> = listOf(1, 2),
    val id: String,
    val name: String,
    val model: String,
    val app: String = "",
    val receiving: Boolean = true,
    val features: List<String> = emptyList(),
    val limits: V2Limits,
    val nonce: String,
)

@Serializable
data class SenderV2(
    val id: String = "",
    val name: String = "",
    val model: String = "",
    val app: String = "",
    /** The port of the sender's own API, so the receiver can send back. Required for `hello`. */
    val port: Int? = null,
)

/** One entry of a send: a file, or an empty folder when [kind] is "dir". [mtime] is in milliseconds since 1970. */
@Serializable
data class FileEntry(
    val id: String,
    val name: String,
    val size: Long,
    val mtime: Long? = null,
    val kind: String? = null,
    val mime: String? = null,
) {
    val isDir: Boolean get() = kind == KIND_DIR

    companion object {
        const val KIND_DIR = "dir"
    }
}

@Serializable
data class Summary(val count: Long, val bytes: Long, val names: List<String> = emptyList())

@Serializable
data class SessionCreate(
    val session: String,
    val sender: SenderV2,
    val cert: String,
    val nonce: String,
    val proof: String,
    val summary: Summary,
    val files: List<FileEntry> = emptyList(),
    val more: Boolean = false,
    val text: String? = null,
)

/** What the receiver reports for one file: `partial` with the chunks it has, `done` with its saved name, or `skipped` with a reason. */
@Serializable
data class FileState(
    val state: String,
    /** Half-open ranges: [[0, 12], [14, 15]] is chunks 0 to 11 and 14. */
    val chunks: List<List<Long>>? = null,
    val name: String? = null,
    val reason: String? = null,
)

@Serializable
data class SessionState(
    val session: String,
    /** pending, accepted, declined, cancelled, expired or done. */
    val state: String,
    val version: Long,
    @SerialName("chunk_size") val chunkSize: Long,
    @SerialName("in_order") val inOrder: Boolean,
    @SerialName("manifest_complete") val manifestComplete: Boolean,
    @SerialName("expires_in") val expiresIn: Long? = null,
    /** "all", or the list of file IDs the person chose. */
    val wanted: JsonElement? = null,
    val files: Map<String, FileState>? = null,
    /** Only in the answer to `POST /sessions`. */
    val token: String? = null,
) {
    /** The IDs the person chose, or null for "all" (or not answered yet). */
    val wantedIds: Set<String>? get() = (wanted as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet()

    companion object {
        fun wantedAll(): JsonElement = JsonPrimitive("all")
        fun wantedList(ids: Collection<String>): JsonElement = JsonArray(ids.map { JsonPrimitive(it) })
    }
}

@Serializable
data class ManifestPage(val files: List<FileEntry>, val more: Boolean = false)

@Serializable
data class ManifestReply(val ok: Boolean = true, val count: Long)

@Serializable
data class ChunkReply(val ok: Boolean = true, val file: String, val chunk: Long)

@Serializable
data class DoneRequest(val alg: String, val digest: String)

@Serializable
data class DoneReply(val ok: Boolean = true, val name: String)

@Serializable
data class BatchResult(val ok: Boolean, val name: String? = null, val error: String? = null)

@Serializable
data class BatchReply(val results: Map<String, BatchResult>)

@Serializable
data class PairRequest(val sender: SenderV2, val cert: String, val nonce: String, val proof: String, val mac: String)

@Serializable
data class PairReply(val ok: Boolean = true, val id: String, val name: String)

@Serializable
data class HelloRequest(val sender: SenderV2, val cert: String, val nonce: String, val proof: String)

/**
 * A set of chunk numbers, kept as bits. [ranges] gives the half-open ranges the protocol sends ([[0, 5], [7, 8]] is
 * chunks 0 to 4 and 7); [missing] gives what is left of a file of [total] chunks.
 */
class ChunkSet(private val bits: BitSet = BitSet()) {
    val count: Int get() = bits.cardinality()

    operator fun contains(n: Long): Boolean = n in 0..Int.MAX_VALUE && bits.get(n.toInt())

    fun add(n: Long) {
        require(n in 0..Int.MAX_VALUE) { "chunk $n is out of range" }
        bits.set(n.toInt())
    }

    fun remove(n: Long) {
        if (n in 0..Int.MAX_VALUE) bits.clear(n.toInt())
    }

    fun clear() = bits.clear()

    fun isComplete(total: Long): Boolean = total <= Int.MAX_VALUE && bits.nextClearBit(0) >= total

    /** The lowest chunk number not in the set. */
    fun firstMissing(): Long = bits.nextClearBit(0).toLong()

    fun ranges(): List<List<Long>> = rangesOf(bits, 0, bits.length())

    /** The chunks below [total] that are not in the set, as ranges. */
    fun missing(total: Long): List<List<Long>> {
        val gaps = BitSet().apply { set(0, total.toInt()) }
        gaps.andNot(bits)
        return rangesOf(gaps, 0, total.toInt())
    }

    private fun rangesOf(b: BitSet, from: Int, to: Int): List<List<Long>> {
        val out = ArrayList<List<Long>>()
        var i = b.nextSetBit(from)
        while (i in 0 until to) {
            val end = b.nextClearBit(i)
            out += listOf(i.toLong(), minOf(end, to).toLong())
            i = b.nextSetBit(end)
        }
        return out
    }

    companion object {
        fun fromRanges(ranges: List<List<Long>>): ChunkSet {
            val set = ChunkSet()
            for (r in ranges) {
                require(r.size == 2 && r[0] in 0..Int.MAX_VALUE && r[1] in r[0]..Int.MAX_VALUE.toLong()) { "bad chunk range $r" }
                set.bits.set(r[0].toInt(), r[1].toInt())
            }
            return set
        }
    }
}
