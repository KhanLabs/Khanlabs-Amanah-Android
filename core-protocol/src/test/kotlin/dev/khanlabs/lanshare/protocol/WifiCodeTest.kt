package dev.khanlabs.lanshare.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WifiCodeTest {
    @Test
    fun writesTheStandardText() {
        // The same string is the golden value in the Windows repo's test_connect_code.py
        assertEquals("WIFI:T:WPA;S:AndroidShare_4821;P:k9d2Lm4qXw;;", WifiCode.toText("AndroidShare_4821", "k9d2Lm4qXw"))
    }

    @Test
    fun escapesTheSpecialCharacters() {
        val ssid = "Khan's Net;1"
        val psk = "pa:ss,wo\\rd\"9" // pa:ss,wo<backslash>rd<quote>9
        val text = WifiCode.toText(ssid, psk)
        assertEquals("WIFI:T:WPA;S:Khan's Net\\;1;P:pa\\:ss\\,wo\\\\rd\\\"9;;", text)
        assertEquals(ssid to psk, WifiCode.parse(text))
    }

    @Test
    fun readsWhatPhonesWrite() {
        assertEquals("Home" to "secret123", WifiCode.parse("WIFI:S:Home;T:WPA;P:secret123;;"))
        assertEquals("Home" to "secret123", WifiCode.parse("WIFI:T:WPA2;S:\"Home\";P:\"secret123\";H:false;;"))
        assertEquals("Home" to "secret123", WifiCode.parse("wifi:t:wpa;s:Home;p:secret123;;"))
        assertEquals("Home" to "secret123", WifiCode.parse("  WIFI:T:WPA;S:Home;P:secret123  "))
    }

    @Test
    fun aQuoteInsideTheValueIsKept() {
        assertEquals("\"Net\"" to "secret123", WifiCode.parse("WIFI:T:WPA;S:\\\"Net\\\";P:secret123;;"))
    }

    @Test
    fun refusesWhatItCannotUse() {
        for (text in listOf(
            "", "https://example.com", "WIFI:T:nopass;S:Cafe;;", "WIFI:T:WEP;S:Old;P:12345;;", "WIFI:T:WPA;S:Home;;",
            "WIFI:T:WPA;P:secret123;;", "WIFI:T:WPA;S:Home;P:short;;", "WIFI:T:WPA;S:;P:secret123;;",
            "WIFI:T:WPA;S:" + "n".repeat(33) + ";P:secret123;;", "WIFI:T:WPA;S:Home;P:" + "p".repeat(64) + ";;",
            "WIFI:T:WPA;S:Home;P:" + "p".repeat(400) + ";;", "WIFI:S:Home;P:secret123;;",
        )) assertNull(WifiCode.parse(text), text.take(60))
    }

    @Test
    fun theRulesForANameAndPassword() {
        assertTrue(WifiCode.isValid("a", "12345678"))
        assertTrue(WifiCode.isValid("日".repeat(10), "p".repeat(63)))
        assertFalse(WifiCode.isValid("日".repeat(11), "12345678"))
        assertFalse(WifiCode.isValid("a\nb", "12345678"))
        assertFalse(WifiCode.isValid("net", "1234567"))
        assertFalse(WifiCode.isValid("net", "pässword1")) // not ASCII
    }
}
