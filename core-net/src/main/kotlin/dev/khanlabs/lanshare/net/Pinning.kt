package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.ConnectCode
import dev.khanlabs.lanshare.protocol.DeviceId
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** The device at that address has a different certificate than the one we know. */
class IdentityMismatchException(val expected: String, val actual: String) :
    CertificateException("That device's identity has changed. If it was reinstalled, remove it and add it again.")

/**
 * There is no certificate authority between devices. Instead of the usual checks, the client pins the device ID
 * (PROTOCOL.md section 1): the server's certificate must hash to the ID we expect. With no expected ID (the first
 * contact by IP address) any certificate is let through, and the caller reads the ID from the handshake.
 */
class PinningTrustManager(private val expectedId: String?) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val cert = chain?.firstOrNull() ?: throw CertificateException("The device sent no certificate")
        val id = DeviceId.of(cert.encoded)
        if (expectedId != null && id != expectedId) throw IdentityMismatchException(expectedId, id)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not used")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** The address is not on this network, so the app does not connect to it. */
class NotLocalException(val host: String) :
    IOException("That address isn't on your local network, so Amanah won't connect to it.")

/**
 * OkHttp clients for talking to other devices, one per pinned ID. They connect only to addresses on this network or
 * this device (see [ConnectCode.isLocalAddress]), so a wrong code or a made-up device entry cannot send files to
 * the internet. [allowVpn] says whether the range VPNs use (100.64.0.0/10) also counts, as the Settings switch says.
 */
class Clients(private val base: OkHttpClient = OkHttpClient(), private val allowVpn: () -> Boolean = { false }) {
    private val cache = ConcurrentHashMap<String, OkHttpClient>()
    private val onlyLocal = Interceptor { chain ->
        val host = chain.request().url.host
        if (!ConnectCode.isLocalAddress(host, allowVpn())) throw NotLocalException(host)
        chain.proceed(chain.request())
    }

    /** Never retries on its own: an offer sent twice would be refused as a duplicate. One request per connection (protocol v1). */
    fun pinned(expectedId: String?): OkHttpClient = cache.getOrPut(expectedId ?: "") { build(expectedId, keepAlive = false) }

    /**
     * For protocol v2: connections are kept and used again (PROTOCOL-v2.md section 1.1), so many small requests don't
     * each pay for a TLS handshake. The device ID is checked when a connection is made, as the spec says, and
     * [PEER_ID] still says which certificate each answer came over. Never retries on its own either.
     */
    fun pinnedV2(expectedId: String?): OkHttpClient = cacheV2.getOrPut(expectedId ?: "") { build(expectedId, keepAlive = true) }

    private val cacheV2 = ConcurrentHashMap<String, OkHttpClient>()

    private fun build(expectedId: String?, keepAlive: Boolean): OkHttpClient {
        val trust = PinningTrustManager(expectedId)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val builder = base.newBuilder()
            .sslSocketFactory(tls.socketFactory, trust)
            .hostnameVerifier { _, _ -> true } // the pin above replaces the host name check
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(onlyLocal)
        if (keepAlive) builder.connectionPool(ConnectionPool(8, 50, TimeUnit.SECONDS)) // the receiver drops a connection idle for 60 s
        else builder.addInterceptor(oneRequestPerConnection)
        return builder.addNetworkInterceptor(peerId).build()
    }

    companion object {
        /**
         * Response header with the device ID of the certificate the connection used. OkHttp's own
         * handshake.peerCertificates is empty for self-signed certificates, so it is read from the TLS socket.
         */
        const val PEER_ID = "X-KL-Peer-Id"

        /**
         * Protocol v1: a new connection, and so a new pin check, for every request. The Windows app answers with
         * HTTP/1.0 and closes the connection, and OkHttp would otherwise try to reuse it.
         */
        private val oneRequestPerConnection = Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Connection", "close").build())
        }

        private val peerId = Interceptor { chain ->
            val cert = (chain.connection()?.socket() as? SSLSocket)?.session?.peerCertificates?.firstOrNull()
            val response = chain.proceed(chain.request())
            if (cert == null) response else response.newBuilder().header(PEER_ID, DeviceId.of(cert.encoded)).build()
        }

        /** The [IdentityMismatchException] somewhere in an exception's causes, if there is one. */
        fun mismatch(e: Throwable): IdentityMismatchException? {
            var t: Throwable? = e
            repeat(10) {
                if (t is IdentityMismatchException) return t as IdentityMismatchException
                t = t?.cause ?: return null
            }
            return null
        }
    }
}
