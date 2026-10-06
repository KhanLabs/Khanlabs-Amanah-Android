package dev.khanlabs.lanshare.net

import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Search this network", over real TLS on 127.0.0.1. */
class ScanTest {
    private lateinit var tmp: File
    private lateinit var rx: Receiver
    private val clients = Clients()
    private val identity = Identity.create("test")

    @BeforeTest
    fun start() {
        tmp = Files.createTempDirectory("klshare-scan-").toFile()
        rx = startConformanceReceiver(File(tmp, "inbox"), 47920, identity = identity).first
    }

    @AfterTest
    fun stop() {
        rx.stop()
        tmp.deleteRecursively()
    }

    @Test
    fun findsADeviceByAskingAddresses() {
        val seen = ArrayList<Pair<Int, Int>>()
        val found = Scan.run(clients, emptyList(), "", hosts = listOf("127.0.0.1", "127.0.0.1"), ports = listOf(rx.port),
            progress = { done, total -> seen += done to total })
        assertEquals(listOf(identity.id), found.map { it.id }) // one device, though two addresses answered
        assertEquals("Conformance Android", found[0].name)
        assertEquals(rx.port, found[0].port)
        assertEquals(2 to 2, seen.last())
        assertEquals(emptyList(), Scan.run(clients, emptyList(), identity.id, hosts = listOf("127.0.0.1"), ports = listOf(rx.port)))
    }

    @Test
    fun triesTheNextPortWhenOneIsRefused() {
        val real: (String, Int, Int) -> Unit = { host, port, timeout ->
            if (port == rx.port - 1) throw ConnectException("Connection refused") else java.net.Socket().use {
                it.connect(java.net.InetSocketAddress(host, port), timeout)
            }
        }
        val found = Scan.run(clients, emptyList(), "", hosts = listOf("127.0.0.1"), ports = listOf(rx.port - 1, rx.port), connect = real)
        assertEquals(listOf(identity.id), found.map { it.id })
    }

    @Test
    fun skipsAHostThatDoesNotAnswerAtAll() {
        val tried = ArrayList<Int>()
        val silent: (String, Int, Int) -> Unit = { _, port, _ -> synchronized(tried) { tried += port }; throw SocketTimeoutException() }
        assertEquals(emptyList(), Scan.run(clients, emptyList(), "", hosts = listOf("127.0.0.1"), ports = listOf(1, 2, 3), connect = silent))
        assertEquals(listOf(1), tried) // one try, not one per port
    }

    @Test
    fun canBeStopped() {
        val found = Scan.run(clients, emptyList(), "", stop = { true }, hosts = List(5) { "127.0.0.1" }, ports = listOf(rx.port))
        assertEquals(emptyList(), found)
    }

    @Test
    fun onlyAsksPrivateAddressesNearThisDevice() {
        val hosts = Scan.hosts(listOf("192.168.1.20", "8.8.8.8", "10.0.5.7", "192.168.1.21", "not an address", "100.64.0.5"))
        assertTrue("192.168.1.1" in hosts && "192.168.1.254" in hosts)
        assertFalse("192.168.1.20" in hosts) // this device's own addresses are not asked
        assertFalse("192.168.1.21" in hosts)
        assertFalse("192.168.1.255" in hosts || "192.168.1.0" in hosts)
        assertTrue("10.0.5.1" in hosts)
        assertEquals(hosts.size, hosts.toSet().size)
        assertTrue(hosts.none { it.startsWith("8.8.8.") || it.startsWith("100.64.") })
        assertEquals(252 + 253, hosts.size) // 254 per network, minus this device's own addresses
    }
}
