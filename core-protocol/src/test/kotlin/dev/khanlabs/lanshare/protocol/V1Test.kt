package dev.khanlabs.lanshare.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class V1Test {
    @Test
    fun namesMatchTheWindowsApp() {
        // The same answers as the Windows app's clean_relpath(), checked there on 2026-10-05.
        val refused = listOf("../x", "/abs", "a/../b", "a\b", "CON", "a/NUL.txt", "trail.", "x:y", "", "sp ",
            "..\\up.txt", "ctl\u0001.txt", "x".repeat(256))
        for (n in refused) assertNull(Names.cleanRelPath(n), n)
        assertEquals("a/b", Names.cleanRelPath("a//b"))
        assertEquals("a/b", Names.cleanRelPath("a/./b"))
        assertEquals("a/b.txt", Names.cleanRelPath("a\\b.txt"))
        assertEquals("ok/fine.txt", Names.cleanRelPath("ok/fine.txt"))
        assertEquals("x".repeat(255), Names.cleanRelPath("x".repeat(255)))
        // 255 emoji are 510 UTF-16 units but 255 characters to Python, so still allowed
        val emoji = String(Character.toChars(0x1F600)).repeat(255)
        assertEquals(emoji, Names.cleanRelPath(emoji))
    }

    @Test
    fun safeNamesPassTheWindowsRules() {
        for (n in listOf("Screenshot 12:30.png", "CON.txt", "what?.jpg", "trail. ", "..", "", "a\u0001b", "x".repeat(300) + ".mp4")) {
            val safe = Names.safePart(n)
            assertEquals(safe, Names.cleanRelPath(safe), "$n -> $safe")
        }
        assertEquals("Screenshot 12_30.png", Names.safePart("Screenshot 12:30.png"))
        assertEquals("CON_.txt", Names.safePart("CON.txt"))
        assertEquals("Trip/day 1/a_b.jpg", Names.safeRelPath(listOf("Trip", "day 1", "a|b.jpg")))
    }

    @Test
    fun numberedNames() {
        assertEquals("a.txt", Names.numbered("a.txt", 0))
        assertEquals("a (2).txt", Names.numbered("a.txt", 2))
        assertEquals("README (1)", Names.numbered("README", 1))
        assertEquals(".bashrc (1)", Names.numbered(".bashrc", 1))
    }

    private fun check(json: String) = OfferCheck.check(OfferCheck.parseObject(json.toByteArray()))

    private fun code(json: String) = assertFailsWith<ProtocolException> { check(json) }.code

    @Test
    fun goodOffer() {
        val o = check("""{"offer":"q3V9xY2kL0aB7cD4eF6gH8","sender":{"id":"AB","name":"PC","extra":1},
            "files":[{"id":"0","name":"Trip//a.jpg","size":5},{"id":1,"name":"b.txt","size":0}],"text":"hi"}""")
        assertEquals(listOf("Trip/a.jpg", "b.txt"), o.files.map { it.name })
        assertEquals(listOf("0", "1"), o.files.map { it.id })
        assertEquals("PC", o.sender.name)
        assertEquals(5L, o.totalBytes)
        assertEquals("hi", o.text)
    }

    @Test
    fun theKindMessageNeedsTextAndNoFiles() {
        val ok = """"offer":"q3V9xY2kL0aB7cD4eF6gH8""""
        assertEquals("message", check("""{$ok,"kind":"message","text":"hi"}""").kind)
        assertEquals("", check("""{$ok,"text":"hi"}""").kind)
        assertEquals("", check("""{$ok,"kind":"other","text":"hi"}""").kind)
        assertEquals("", check("""{$ok,"kind":5,"text":"hi"}""").kind)
        assertEquals("", check("""{$ok,"kind":"message","text":"hi","files":[{"id":"0","name":"a","size":1}]}""").kind)
        assertEquals("empty", code("""{$ok,"kind":"message"}"""))
    }

    @Test
    fun badOffersGetTheSameCodesAsWindows() {
        val ok = """"offer":"q3V9xY2kL0aB7cD4eF6gH8""""
        assertEquals("bad_json", assertFailsWith<ProtocolException> { OfferCheck.parseObject("{nope".toByteArray()) }.code)
        assertEquals("bad_json", assertFailsWith<ProtocolException> { OfferCheck.parseObject("[1]".toByteArray()) }.code)
        assertEquals("bad_offer", code("""{"offer":"short","text":"x"}"""))
        assertEquals("bad_offer", code("""{"offer":12345678901234567890,"text":"x"}"""))
        assertEquals("bad_text", code("""{$ok,"text":5}"""))
        assertEquals("bad_text", code("""{$ok,"text":"${"x".repeat(64 * 1024 + 1)}"}"""))
        assertEquals("bad_files", code("""{$ok,"files":{"a":1}}"""))
        assertEquals("bad_files", code("""{$ok,"files":[{"id":"0","name":"a","size":"1"}]}"""))
        assertEquals("bad_files", code("""{$ok,"files":[{"id":"0","name":"a","size":1.5}]}"""))
        assertEquals("bad_files", code("""{$ok,"files":[{"id":"0","name":"a","size":-1}]}"""))
        assertEquals("bad_files", code("""{$ok,"files":[{"id":"0","name":"a","size":1},{"id":"0","name":"b","size":1}]}"""))
        assertEquals("bad_files", code("""{$ok,"files":[{"id":"0","name":"../a","size":1}]}"""))
        assertEquals("bad_files", code("""{$ok,"files":[{"name":"a","size":1}]}"""))
        assertEquals("empty", code("""{$ok}"""))
        assertEquals("empty", code("""{$ok,"files":[],"text":""}"""))
    }
}
