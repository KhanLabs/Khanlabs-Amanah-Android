package dev.khanlabs.lanshare.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The web page of Share to anyone looks like the app and says who made it. The Windows page has the same CSS. */
class SharePageLookTest {
    private val page = SharePage.ended("Amanah 1.0")

    private fun tokens(from: String): Map<String, String> {
        val start = page.indexOf(from)
        val block = page.substring(start, page.indexOf('}', start))
        return Regex("--([a-z0-9-]+):\\s*(#[0-9a-fA-F]{6})").findAll(block).associate { it.groupValues[1] to it.groupValues[2].uppercase() }
    }

    @Test
    fun theHeaderSaysAmanahByKhanLabs() {
        assertTrue("""<span class="brand-mark">Amanah</span>""" in page)
        assertTrue(""">by KhanLabs.dev</a>""" in page)
        assertTrue("— Amanah by KhanLabs.dev</title>" in page)
        assertTrue(">KhanLabs.dev</a>" in page) // the footer link
    }

    @Test
    fun theColoursAreTheAppsInLightAndDark() {
        val light = tokens(":root {")
        assertEquals("#FEF7FF", light["paper"]); assertEquals("#F7F2FA", light["card"]); assertEquals("#E6E0E9", light["raised"])
        assertEquals("#1D1B20", light["ink"]); assertEquals("#49454F", light["muted"]); assertEquals("#CAC4D0", light["border"])
        assertEquals("#0F766E", light["accent"]); assertEquals("#FFFFFF", light["accent-ink"]); assertEquals("#C62828", light["brick"])
        val dark = tokens("@media (prefers-color-scheme: dark) {")
        assertEquals("#0B0D0E", dark["paper"]); assertEquals("#151A1C", dark["card"]); assertEquals("#1D2427", dark["raised"])
        assertEquals("#E8EEF0", dark["ink"]); assertEquals("#A9B4B8", dark["muted"]); assertEquals("#2E3639", dark["border"])
        assertEquals("#2DD4BF", dark["accent"]); assertEquals("#0B0D0E", dark["accent-ink"]); assertEquals("#FF5F57", dark["brick"])
    }

    @Test
    fun oneTypefaceFlatSurfacesAndNothingFromTheOldLook() {
        val style = page.substring(page.indexOf("<style>"), page.indexOf("</style>"))
        assertFalse("Georgia" in style)
        assertFalse("gradient" in style || "backdrop-filter" in style || "blur" in style)
        assertFalse("--clay" in page)
        // corners come from one family: 8, 12, 20 and 28, plus pills and circles
        val allowed = setOf("var(--r8)", "var(--r12)", "var(--r20)", "var(--r28)", "999px", "50%", "23%", "0")
        for (m in Regex("border-radius:\\s*([^;}]+)").findAll(style)) {
            assertTrue(m.groupValues[1].trim().split(" ").all { it in allowed }, m.groupValues[1])
        }
    }

    @Test
    fun theIconIsTheAmanahMarkNotTheOldK() {
        assertTrue("""<circle cx="128" cy="154" r="16.5" fill="#FEF7FF"/>""" in page)
        assertFalse(">K</text>" in page)
    }

    @Test
    fun everySlotInTheNewPageIsFilled() {
        assertFalse("{{" in SharePage.notFound("1.0"))
        assertFalse("{{" in SharePage.login("1.0"))
    }
}
