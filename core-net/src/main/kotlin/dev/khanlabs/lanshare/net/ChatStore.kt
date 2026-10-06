package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.DeviceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom

/** One text in a conversation. [state] is sending, done, declined or failed (only texts you sent are ever not done). */
data class ChatMessage(
    val id: String,
    /** Milliseconds since 1970. */
    val at: Long,
    /** The other device's ID, as it announced it. */
    val peer: String,
    /** The other device's name when this text was written. */
    val name: String,
    val incoming: Boolean,
    val text: String,
    val state: String = "done",
    /** Why it wasn't delivered, when it wasn't. */
    val note: String = "",
    val read: Boolean = true,
)

data class Conversation(val peer: String, val name: String, val last: ChatMessage, val unread: Int)

/**
 * Conversations with other devices: every text sent or received, one JSON line each, kept in [file] (null keeps
 * them in memory only). Nothing leaves this phone. The same shape as the Windows app's chats.py.
 */
class ChatStore(private val file: File? = null, private val keep: Int = KEEP, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val random = SecureRandom()

    /** Oldest first. A new list is made on every change, so this can be read from any thread. */
    @Volatile var all: List<ChatMessage> = load()
        private set

    fun add(peer: String, name: String, incoming: Boolean, text: String, state: String = "done", note: String = "",
            read: Boolean = !incoming): ChatMessage {
        val m = ChatMessage(newId(), clock(), peer, name.take(63), incoming, text, state, note, read)
        synchronized(lock) {
            all = all + m
            append(m)
        }
        return m
    }

    /** Null when nothing changed. */
    fun setState(id: String, state: String, note: String = ""): ChatMessage? = synchronized(lock) {
        val old = all.firstOrNull { it.id == id } ?: return null
        if (old.state == state && old.note == note) return null
        val m = old.copy(state = state, note = note)
        all = all.map { if (it.id == id) m else it }
        rewrite()
        m
    }

    /** True when something was unread. */
    fun markRead(peer: String): Boolean = synchronized(lock) {
        if (all.none { it.peer == peer && !it.read }) return false
        all = all.map { if (it.peer == peer && !it.read) it.copy(read = true) else it }
        rewrite()
        true
    }

    fun forget(peer: String) = synchronized(lock) {
        all = all.filter { it.peer != peer }
        rewrite()
    }

    /**
     * True when a text with that device has really gone through: one you sent that it accepted, or one it sent that
     * you accepted. A text it declined, or that never arrived, does not count, so trying to reach a device never
     * lets it skip the prompt.
     */
    fun knows(peer: String): Boolean = all.any { it.peer == peer && it.state == "done" }

    fun thread(peer: String): List<ChatMessage> = all.filter { it.peer == peer }

    fun unread(peer: String? = null): Int = all.count { !it.read && (peer == null || it.peer == peer) }

    fun conversations(): List<Conversation> = conversations(all)

    private fun newId() = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    // --- the file

    private fun load(): List<ChatMessage> {
        val f = file ?: return emptyList()
        val lines = try {
            f.readLines()
        } catch (e: Exception) {
            return emptyList() // no file yet
        }
        val found = lines.takeLast(keep).mapNotNull { parse(it) }
        if (lines.size > keep) synchronized(lock) { write(found) }
        return found
    }

    private fun parse(line: String): ChatMessage? = try {
        val o = Json.parseToJsonElement(line) as JsonObject
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val state = str("state") ?: "done"
        ChatMessage(
            id = str("id")!!, at = (o["at"] as JsonPrimitive).longOrNull!!, peer = str("peer")!!.takeIf { it.isNotEmpty() }!!,
            name = str("name") ?: "", incoming = (o["in"] as JsonPrimitive).booleanOrNull!!, text = str("text")!!,
            // the app closed before it finished
            state = if (state == "sending") "failed" else state, note = str("note") ?: "",
            read = (o["read"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    } catch (e: Exception) {
        null
    }

    private fun encode(m: ChatMessage): String = buildJsonObject {
        put("id", m.id); put("at", m.at); put("peer", m.peer); put("name", m.name); put("in", m.incoming)
        put("text", m.text); put("state", m.state); put("note", m.note); put("read", m.read)
    }.toString()

    private fun append(m: ChatMessage) {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            f.appendText(encode(m) + "\n")
        } catch (e: Exception) {
            // a conversation is a convenience: a full disk must not stop anything else
        }
    }

    private fun rewrite() = write(all)

    private fun write(list: List<ChatMessage>) {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(list.joinToString("") { encode(it) + "\n" })
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // see append
        }
    }

    companion object {
        /** Older lines are dropped when the file is read at start-up. */
        const val KEEP = 2000

        /** Most recent conversation first. The name is the latest one seen. */
        fun conversations(all: List<ChatMessage>): List<Conversation> {
            val last = LinkedHashMap<String, Int>()
            val unread = HashMap<String, Int>()
            val name = HashMap<String, String>()
            for ((i, m) in all.withIndex()) {
                last[m.peer] = i // the position, not the clock: two texts can share a millisecond
                if (m.name.isNotEmpty()) name[m.peer] = m.name
                if (!m.read) unread[m.peer] = (unread[m.peer] ?: 0) + 1
            }
            return last.entries.sortedByDescending { it.value }
                .map { (peer, i) -> Conversation(peer, name[peer] ?: "", all[i], unread[peer] ?: 0) }
        }

        /**
         * Which conversation a text belongs to: the device found at the address it came from, else the ID the sender
         * announced, else the address itself. The address comes first because the ID in an offer proves nothing, so a
         * stranger can't put a text into a friend's conversation by quoting the friend's ID.
         */
        fun peerOf(senderId: String, ip: String, devices: List<Device>): String {
            devices.firstOrNull { ip in it.hosts }?.let { return it.id }
            val id = senderId.uppercase()
            // a device with that ID is on the network at another address, so this text is not from it
            if (!DeviceId.isValid(id) || devices.any { it.id == id }) return "ip:$ip"
            return id
        }
    }
}
