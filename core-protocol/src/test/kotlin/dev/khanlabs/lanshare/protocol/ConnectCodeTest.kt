package dev.khanlabs.lanshare.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectCodeTest {
    private val id = "A1B2C3D4".repeat(8)

    @Test
    fun roundTrip() {
        val code = ConnectCode(id, "Khan's PC 日本語", listOf("192.168.1.232", "fe80::1"), 47820)
        assertEquals(code, ConnectCode.parse(code.toText()))
    }

    @Test
    fun writesTheSameBytesAsTheWindowsApp() {
        // The same string is the golden value in the Windows repo's test_connect_code.py
        val golden = "klshare://connect?v=1&id=$id&name=Khan%27s+PC&ip=192.168.1.232&port=47820"
        assertEquals(golden, ConnectCode(id, "Khan's PC", listOf("192.168.1.232"), 47820).toText())
    }

    @Test
    fun readsWhatThePythonSideWrites() {
        // urllib.parse.urlencode makes "+" for spaces and %XX for the rest
        val text = "klshare://connect?v=1&id=${id.lowercase()}&name=Khan%27s+PC&ip=192.168.1.232&port=47820"
        val code = assertNotNull(ConnectCode.parse(text))
        assertEquals(id, code.id)
        assertEquals("Khan's PC", code.name)
        assertEquals(listOf("192.168.1.232"), code.hosts)
        assertEquals(47820, code.port)
    }

    @Test
    fun theLaterPairFormIsReadAndTheSecretIgnored() {
        val code = ConnectCode.parse("klshare://pair?v=2&id=$id&name=TV&ip=10.0.0.5&port=47820&secret=abcdef")
        assertEquals(listOf("10.0.0.5"), code?.hosts)
    }

    @Test
    fun refusesAddressesOutsideTheLocalNetwork() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "172.15.0.1", "100.64.0.1", "192.169.1.1", "300.1.1.1",
            "example.com", "localhost", "2001:4860:4860::8888", "fe80::zzzz", "")) {
            assertFalse(ConnectCode.isLocalAddress(ip), ip)
            assertNull(ConnectCode.parse("klshare://connect?v=1&id=$id&ip=$ip&port=47820"), ip)
        }
        for (ip in listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.7.7", "127.0.0.1",
            "fe80::1", "fe80::1%wlan0", "fd12:3456::1", "::1")) {
            assertTrue(ConnectCode.isLocalAddress(ip), ip)
        }
    }

    @Test
    fun keepsOnlyTheLocalAddressesFromAMixedCode() {
        val code = ConnectCode.parse("klshare://connect?v=1&id=$id&ip=8.8.8.8&ip=192.168.1.9&port=47820")
        assertEquals(listOf("192.168.1.9"), code?.hosts)
    }

    @Test
    fun refusesAnythingElse() {
        val ok = "klshare://connect?v=1&id=$id&ip=192.168.1.9&port=47820"
        assertNotNull(ConnectCode.parse(ok))
        assertNull(ConnectCode.parse(""))
        assertNull(ConnectCode.parse("https://khanlabs.dev"))
        assertNull(ConnectCode.parse("klshare://other?id=$id&ip=192.168.1.9&port=47820"))
        assertNull(ConnectCode.parse("klshare://connect?v=1&id=${id.dropLast(1)}&ip=192.168.1.9&port=47820"))
        assertNull(ConnectCode.parse("klshare://connect?v=1&id=${"G".repeat(64)}&ip=192.168.1.9&port=47820"))
        assertNull(ConnectCode.parse("klshare://connect?v=1&id=$id&ip=192.168.1.9"))
        assertNull(ConnectCode.parse("klshare://connect?v=1&id=$id&ip=192.168.1.9&port=0"))
        assertNull(ConnectCode.parse("klshare://connect?v=1&id=$id&ip=192.168.1.9&port=70000"))
        assertNull(ConnectCode.parse("klshare://connect?v=1&id=$id&ip=192.168.1.9&port=abc"))
        assertNull(ConnectCode.parse("klshare://connect?v=9&id=$id&ip=192.168.1.9&port=47820"))
        assertNull(ConnectCode.parse("klshare://connect?v=1&id=$id&ip=192.168.1.9&port=47820&name=%zz"))
        assertNull(ConnectCode.parse(ok + "&x=" + "a".repeat(700)))
    }

    @Test
    fun cleansTheName() {
        val code = ConnectCode.parse("klshare://connect?v=1&id=$id&name=%01%02Khan+${"x".repeat(100)}&ip=10.0.0.1&port=1")
        assertEquals(63, code?.name?.length)
        assertTrue(code!!.name.startsWith("Khan "))
    }
}
