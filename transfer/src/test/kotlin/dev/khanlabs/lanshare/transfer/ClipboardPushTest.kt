package dev.khanlabs.lanshare.transfer

import dev.khanlabs.lanshare.net.TrustedDevice
import dev.khanlabs.lanshare.protocol.V1
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardPushTest {
    private val on = TrustedDevice("A".repeat(64), "Pixel", pairedAt = 1, autoClip = true)
    private val off = on.copy(autoClip = false)

    @Test
    fun aV2TextOnItsOwnFromAPairedDeviceWithTheSwitchOnGoesOnTheClipboard() {
        assertTrue(ClipboardPush.shouldCopy(isV2 = true, state = "done", hasFiles = false, text = "Gate B12", sender = on))
    }

    @Test
    fun theSwitchOffOrAnUnpairedSenderCopiesNothing() {
        assertFalse(ClipboardPush.shouldCopy(true, "done", false, "Gate B12", off))
        assertFalse(ClipboardPush.shouldCopy(true, "done", false, "Gate B12", null))
    }

    @Test
    fun aV1TextIsAChatMessageAndNeverCopied() {
        assertFalse(ClipboardPush.shouldCopy(isV2 = false, state = "done", hasFiles = false, text = "hi", sender = on))
    }

    @Test
    fun aSendWithFilesOrOneThatDidNotFinishIsNotAPush() {
        assertFalse(ClipboardPush.shouldCopy(true, "done", true, "a note", on))
        assertFalse(ClipboardPush.shouldCopy(true, "cancelled", false, "x", on))
        assertFalse(ClipboardPush.shouldCopy(true, "declined", false, "x", on))
    }

    @Test
    fun anEmptyTextIsNotCopied() {
        assertFalse(ClipboardPush.shouldCopy(true, "done", false, "", on))
    }

    @Test
    fun nothingCopiedOrOnlyBlanksIsEmpty() {
        assertTrue(ClipboardPush.isEmpty(null))
        assertTrue(ClipboardPush.isEmpty(""))
        assertTrue(ClipboardPush.isEmpty("  \n\t "))
        assertFalse(ClipboardPush.isEmpty(" a "))
    }

    @Test
    fun theLimitIsInBytesNotCharacters() {
        assertFalse(ClipboardPush.tooLong("a".repeat(V1.MAX_TEXT)))
        assertTrue(ClipboardPush.tooLong("a".repeat(V1.MAX_TEXT + 1)))
        // a 3-byte character: 21,846 of them are 65,538 bytes
        assertFalse(ClipboardPush.tooLong("€".repeat(V1.MAX_TEXT / 3)))
        assertTrue(ClipboardPush.tooLong("€".repeat(V1.MAX_TEXT / 3 + 1)))
    }
}
