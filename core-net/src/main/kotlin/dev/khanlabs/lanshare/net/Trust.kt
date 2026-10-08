package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.ProtocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException

/**
 * What the receiver asks the app about trust (PROTOCOL-v2.md section 10). A device is trusted after the person paired
 * it with a QR code; trust is only ever given to a sender that proved its ID, so a v1 offer is never trusted.
 */
interface Trust {
    /** [id] was paired with this device. */
    fun isTrusted(id: String): Boolean

    /** A paired device whose files this device takes without asking. Off for every device until the person turns it on. */
    fun autoAcceptFiles(id: String): Boolean

    /** A paired device whose texts this device takes without asking. */
    fun autoAcceptText(id: String): Boolean

    /**
     * May a sender that is not paired start a send at all? True means the person is asked as usual; false is
     * "Paired devices only": the send is refused before the person is bothered.
     */
    fun acceptsStrangers(): Boolean

    /** The pairing code on screen now, or null when none is. */
    fun pairing(): PairingCode?

    /** A device passed every check of `POST /pair`: remember it as paired. */
    fun paired(id: String, name: String)

    /** The code on screen was used wrongly too often, or was used: take it off the screen. */
    fun withdrawPairing()

    /** No trust at all: everything asks, nothing is auto-accepted, no pairing. */
    object NONE : Trust {
        override fun isTrusted(id: String) = false
        override fun autoAcceptFiles(id: String) = false
        override fun autoAcceptText(id: String) = false
        override fun acceptsStrangers() = true
        override fun pairing(): PairingCode? = null
        override fun paired(id: String, name: String) {}
        override fun withdrawPairing() {}
    }
}

/** A code shown for pairing: its 16-byte secret, when it stops being good, and how often it was answered wrongly. */
class PairingCode(val secret: ByteArray, val expiresAt: Long) {
    @Volatile var wrong = 0

    fun expired(now: Long = System.currentTimeMillis()) = now > expiresAt

    companion object {
        /** A code is good for 10 minutes and for 5 wrong answers (PROTOCOL-v2.md section 10). */
        const val LIFETIME_MS = 10 * 60_000L
        const val MAX_WRONG = 5
    }
}

/** A device the person paired. */
@Serializable
data class TrustedDevice(
    val id: String,
    val name: String,
    val pairedAt: Long,
    /** Take its files without asking. Off until the person turns it on for this device. */
    val autoFiles: Boolean = false,
    /** Take its texts without asking. */
    val autoText: Boolean = false,
    /** Put a text it pushes (a protocol v2 text on its own) on this device's clipboard. Off until the person turns it on. */
    val autoClip: Boolean = false,
)

/** The paired devices, in a file the phone does not back up. Safe to use from any thread. */
class TrustStore(private val file: File) {
    @Volatile private var devices: List<TrustedDevice> = load()

    val all: List<TrustedDevice> get() = devices

    fun get(id: String): TrustedDevice? = devices.firstOrNull { it.id == id }

    /** Adds the device, or renames a device that is already paired (its switches stay as they are). */
    @Synchronized
    fun add(id: String, name: String, now: Long = System.currentTimeMillis()) {
        val old = get(id)
        val updated = if (old == null) TrustedDevice(id, name, now) else old.copy(name = name)
        devices = devices.filter { it.id != id } + updated
        save()
    }

    @Synchronized
    fun remove(id: String) {
        devices = devices.filter { it.id != id }
        save()
    }

    @Synchronized
    fun setAuto(id: String, files: Boolean? = null, text: Boolean? = null, clip: Boolean? = null) {
        val d = get(id) ?: return
        val updated = d.copy(autoFiles = files ?: d.autoFiles, autoText = text ?: d.autoText, autoClip = clip ?: d.autoClip)
        devices = devices.map { if (it.id == id) updated else it }
        save()
    }

    private fun load(): List<TrustedDevice> = try {
        if (file.isFile) ProtocolJson.decodeFromString(ListSerializer(TrustedDevice.serializer()), file.readText(Charsets.UTF_8)) else emptyList()
    } catch (e: Exception) {
        emptyList() // an unreadable list is an empty one: nothing becomes trusted by accident
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(ProtocolJson.encodeToString(ListSerializer(TrustedDevice.serializer()), devices), Charsets.UTF_8)
            file.delete()
            if (!temp.renameTo(file)) throw IOException("Couldn't write $file")
        } catch (e: IOException) {
            // the list stays in memory until the app closes
        }
    }
}

/** Who may send here at all: the person's choice and the 10-minute opening (PROTOCOL-v2.md section 10.3). */
object ReceivePolicy {
    /** Strangers (devices that are not paired) get a prompt, as before. The default. */
    const val ASK = "ask"
    /** Only paired devices may send; everyone else is refused before the person is bothered. */
    const val PAIRED = "paired"

    const val OPEN_MS = 10 * 60_000L

    val CHOICES = listOf(ASK to "Ask every time", PAIRED to "Paired devices only")

    /** True when a device that is not paired may start a send now: [policy] is [ASK], or the 10-minute opening is not over. */
    fun acceptsStrangers(policy: String, openUntil: Long, now: Long = System.currentTimeMillis()): Boolean =
        policy != PAIRED || now < openUntil

    fun describe(policy: String, openUntil: Long, now: Long = System.currentTimeMillis()): String = when {
        policy != PAIRED -> "Anyone on the network can ask to send. You always choose to accept or decline."
        now < openUntil -> "Anyone can ask to send for another ${((openUntil - now) / 60_000 + 1)} minutes. Then only paired devices."
        else -> "Only paired devices can send. Others are turned away before you are asked."
    }
}
