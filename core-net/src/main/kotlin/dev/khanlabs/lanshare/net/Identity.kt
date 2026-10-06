package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.DeviceId
import dev.khanlabs.lanshare.protocol.V2
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * This device's self-signed certificate and key (PROTOCOL.md section 1). The device ID is the SHA-256 of the
 * certificate, so the pair is made once and kept: a new one means a new ID, and every device that knew this one
 * would see "identity changed".
 */
class Identity(val certDer: ByteArray, val privateKey: PrivateKey) {
    val id: String = DeviceId.of(certDer)
    val shortId: String get() = DeviceId.short(id)
    val certificate: X509Certificate = V2.parseCert(certDer)

    /** The TLS server side: this certificate and key, TLS 1.2 or newer. */
    fun serverContext(): SSLContext {
        val password = "klshare".toCharArray() // in memory only; the key itself is stored as plain PKCS#8
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("device", privateKey, password, arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    companion object {
        const val CERT_FILE = "identity-cert.der"
        const val KEY_FILE = "identity-key.p8"
        private const val VALID_DAYS = 3650L
        private const val DAY_MS = 24L * 3600 * 1000

        /** The identity kept in [dir], made on first use. On Android, [dir] is in app-private storage. */
        fun loadOrCreate(dir: File, label: String): Identity {
            val certFile = File(dir, CERT_FILE)
            val keyFile = File(dir, KEY_FILE)
            if (certFile.isFile && keyFile.isFile) {
                try {
                    val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    return Identity(certFile.readBytes(), key)
                } catch (e: Exception) {
                    // unreadable: make a new one below, as if it had never existed
                }
            }
            val identity = create(label)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't create $dir")
            writeFile(keyFile, identity.privateKey.encoded) // the key first, so a crash can't leave a cert alone
            writeFile(certFile, identity.certDer)
            return identity
        }

        /** A new ECDSA P-256 key and a certificate for it, valid for 10 years like the Windows app's. */
        fun create(label: String): Identity {
            val random = SecureRandom()
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
                .generateKeyPair()
            val name = X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, "Amanah ($label)")
                .addRDN(BCStyle.O, "KhanLabs")
                .build()
            val now = System.currentTimeMillis()
            val cert = JcaX509v3CertificateBuilder(name, BigInteger(63, random).add(BigInteger.ONE),
                Date(now - DAY_MS), Date(now + VALID_DAYS * DAY_MS), name, pair.public)
                .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
            return Identity(cert.encoded, pair.private)
        }

        private fun writeFile(file: File, data: ByteArray) {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeBytes(data)
            file.delete()
            if (!temp.renameTo(file)) throw IOException("Couldn't write $file")
        }
    }
}
