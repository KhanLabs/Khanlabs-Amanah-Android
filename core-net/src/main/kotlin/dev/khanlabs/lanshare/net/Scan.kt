package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.V1
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

/**
 * "Search this network": for networks where announcements do not get through (some routers and guest Wi-Fi). Asks the
 * addresses near this device's own, one by one, whether an Amanah device answers there. Same rules as the Windows
 * app's `transfer.scan`. Runs only when the person asks, never in the background.
 */
object Scan {
    /** The ports a device uses: 47820, or the next ones when it was taken. */
    val PORTS: List<Int> = (V1.DEFAULT_PORT until V1.DEFAULT_PORT + V1.PORT_TRIES).toList()
    const val CONNECT_TIMEOUT_MS = 600
    const val WORKERS = 64

    private val V4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

    private fun isPrivateV4(ip: String): Boolean {
        val m = V4.matchEntire(ip) ?: return false
        val p = m.groupValues.drop(1).map { it.toInt() }
        if (p.any { it > 255 }) return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }

    /** The addresses in the /24 around each private address in [addresses], except [addresses] themselves. */
    fun hosts(addresses: List<String>): List<String> {
        val own = addresses.toSet()
        val result = LinkedHashSet<String>()
        for (ip in addresses) {
            if (!isPrivateV4(ip)) continue
            val prefix = ip.substringBeforeLast('.')
            for (n in 1..254) {
                val host = "$prefix.$n"
                if (host !in own) result += host
            }
        }
        return result.toList()
    }

    private fun tcpConnect(host: String, port: Int, timeoutMs: Int) {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
    }

    /**
     * The device answering at [host], or null. A host that does not answer at all is skipped after one try; a host
     * that refuses (it is there, the app is not on that port) is tried on the next port.
     */
    private fun probe(clients: Clients, host: String, ports: List<Int>, timeoutMs: Int,
                      connect: (String, Int, Int) -> Unit): Device? {
        for (port in ports) {
            try {
                connect(host, port, timeoutMs)
            } catch (e: ConnectException) {
                if ("refused" in e.message.orEmpty().lowercase()) continue
                return null
            } catch (e: Exception) {
                return null
            }
            try {
                return fetchInfo(clients, host, port, null, timeoutS = 3)
            } catch (e: Exception) {
                // something else is listening on that port: try the next
            }
        }
        return null
    }

    /**
     * Devices found, without [ownId]. [stop] is asked before each address; [progress] is called as addresses are
     * checked (on the calling thread). Blocks, so call it on a worker thread.
     */
    fun run(
        clients: Clients,
        addresses: List<String>,
        ownId: String,
        stop: () -> Boolean = { false },
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        hosts: List<String> = hosts(addresses),
        ports: List<Int> = PORTS,
        workers: Int = WORKERS,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        connect: (String, Int, Int) -> Unit = ::tcpConnect,
    ): List<Device> {
        val found = ArrayList<Device>()
        if (hosts.isEmpty()) return found
        val pool = Executors.newFixedThreadPool(minOf(workers, hosts.size)) { r -> Thread(r, "klshare-scan").apply { isDaemon = true } }
        try {
            val done = ExecutorCompletionService<Device?>(pool)
            for (host in hosts) done.submit { if (stop()) null else probe(clients, host, ports, connectTimeoutMs, connect) }
            for (i in 1..hosts.size) {
                val device = try {
                    done.take().get()
                } catch (e: Exception) {
                    null
                }
                if (device != null && device.id != ownId && found.none { it.id == device.id }) found += device
                progress(i, hosts.size)
            }
        } finally {
            pool.shutdownNow()
        }
        return found
    }
}
