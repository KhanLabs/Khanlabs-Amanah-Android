package dev.khanlabs.lanshare.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/** One finished send or receive, for the Activity tab. Kept on this phone only. */
data class HistoryEntry(
    val id: String,
    val incoming: Boolean,
    /** The other device's name when it happened. */
    val peer: String,
    /** done, partial, declined, failed or cancelled */
    val state: String,
    val files: Int,
    val bytes: Long,
    /** Milliseconds since 1970. */
    val at: Long,
    /** Names as saved (incoming) or sent (outgoing), at most [History.MAX_NAMES]. */
    val names: List<String>,
    val text: String,
    /** Why it failed, when it did. */
    val note: String = "",
)

/**
 * The last [MAX] transfers, in a file the phone doesn't back up. Nothing here leaves the phone; the person can
 * clear it from Settings.
 */
class History(private val file: File) {
    @Volatile var all: List<HistoryEntry> = load()
        private set

    @Synchronized
    fun add(e: HistoryEntry) {
        all = (listOf(e) + all.filter { it.id != e.id }).take(MAX)
        save()
    }

    @Synchronized
    fun remove(id: String) {
        all = all.filter { it.id != id }
        save()
    }

    @Synchronized
    fun clear() {
        all = emptyList()
        save()
    }

    private fun load(): List<HistoryEntry> = try {
        Json.parseToJsonElement(file.readText()).jsonArray.map { e ->
            val o = e.jsonObject
            HistoryEntry(
                id = o["id"]!!.jsonPrimitive.content,
                incoming = o["in"]!!.jsonPrimitive.boolean,
                peer = o["peer"]!!.jsonPrimitive.content,
                state = o["state"]!!.jsonPrimitive.content,
                files = o["files"]!!.jsonPrimitive.int,
                bytes = o["bytes"]!!.jsonPrimitive.long,
                at = o["at"]!!.jsonPrimitive.long,
                names = o["names"]!!.jsonArray.map { it.jsonPrimitive.content },
                text = o["text"]!!.jsonPrimitive.content,
                note = o["note"]?.jsonPrimitive?.content ?: "",
            )
        }
    } catch (e: Exception) {
        emptyList() // no file yet, or a broken one: start empty
    }

    private fun save() {
        val json = JsonArray(all.map { e ->
            buildJsonObject {
                put("id", e.id); put("in", e.incoming); put("peer", e.peer); put("state", e.state)
                put("files", e.files); put("bytes", e.bytes); put("at", e.at)
                put("names", JsonArray(e.names.map { JsonPrimitive(it) }))
                put("text", e.text); put("note", e.note)
            }
        })
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            // history is a convenience: a full disk must not break a transfer
        }
    }

    companion object {
        const val MAX = 200
        const val MAX_NAMES = 100
    }
}
