package dev.khanlabs.lanshare.transfer

import dev.khanlabs.lanshare.net.TrustedDevice
import dev.khanlabs.lanshare.protocol.V1

/** The rules of a clipboard push: what may be sent, and what this device puts on its own clipboard. */
object ClipboardPush {
    /** A text is sent whole or not at all: a receiver takes at most [V1.MAX_TEXT] bytes. */
    fun tooLong(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size > V1.MAX_TEXT

    /** Nothing copied, or only blanks: there is nothing to push. */
    fun isEmpty(text: CharSequence?): Boolean = text.isNullOrBlank()

    /**
     * Does a finished incoming send go on this device's clipboard? Only a protocol v2 send (its sender proved who it is)
     * that is a text on its own, from a paired device the person turned **Put its texts on my clipboard** on for. A chat
     * text arrives over v1 and a send with files has files, so neither is a push.
     */
    fun shouldCopy(isV2: Boolean, state: String, hasFiles: Boolean, text: String, sender: TrustedDevice?): Boolean =
        isV2 && state == "done" && !hasFiles && text.isNotEmpty() && sender?.autoClip == true
}
