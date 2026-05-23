package com.wyspr.feature.onboarding.share

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Build a self-signed X.509 certificate for the device's LAN IP and
 * wrap it in an [SSLServerSocketFactory] suitable for NanoHTTPD's
 * `makeSecure(...)`. The cert is generated fresh every share session
 * — there is no on-disk keystore, no persistent identity, and no
 * material that survives the process.
 *
 * ## Why self-signed at all
 *
 * The peer-to-peer share flow runs over the local network between
 * two phones that just looked at each other's screens. There's no
 * CA infrastructure to trust, no DNS to resolve, no certificate
 * pinning to do. The TLS layer here exists only so:
 *
 *   - Brave's "HTTPS-Only" mode (and Chrome's future hardening of
 *     it) accepts the connection at all. Without this, the page
 *     never loads on those browsers and the user has no way to
 *     install Wyspr.
 *   - The transport is encrypted opportunistically. The mini-site
 *     itself is not secret, but the APK download benefits from at
 *     least preventing a passive same-LAN observer from caching
 *     or modifying the bytes in flight.
 *
 * Real security comes from the SHA-256 hash printed on the
 * mini-site, which the recipient cross-checks against the value
 * the Inviter's device shows them. PKI is irrelevant to that.
 *
 * ## Certificate shape
 *
 *   - Subject + Issuer: `CN=Wyspr Share (<ip>)`
 *   - SAN: IP-address [ip] — needed for Chrome's IP-only host
 *     validation. Without this Chrome rejects with
 *     `NET::ERR_CERT_COMMON_NAME_INVALID`.
 *   - Validity: now → now + 24h. The cert is throwaway.
 *   - Key: RSA-2048. Faster than EC during the connect phase on
 *     low-end devices; still ≥ 112-bit strength.
 *   - Signature: SHA-256 with RSA.
 */
internal object SelfSignedCert {

    /**
     * Generate a fresh keypair + cert for [ip] and return an
     * SSL server socket factory ready to hand to
     * [fi.iki.elonen.NanoHTTPD.makeSecure].
     */
    fun makeSocketFactory(ip: String): SSLServerSocketFactory {
        val keyPair = generateKeyPair()
        val cert = generateCertificate(keyPair, ip)
        val keyStore = wrapInKeyStore(keyPair, cert)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, KEYSTORE_PASSWORD)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx.serverSocketFactory
    }

    private fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, SecureRandom())
        return gen.generateKeyPair()
    }

    private fun generateCertificate(keyPair: KeyPair, ip: String): X509Certificate {
        val now = Date()
        val notAfter = Date(now.time + VALIDITY_MS)
        val subject = X500Name("CN=Wyspr Share ($ip)")
        // SecureRandom-derived 80-bit serial: large enough to dodge
        // accidental reuse, small enough to keep cert size lean.
        val serial = BigInteger(80, SecureRandom()).abs()
        val builder = JcaX509v3CertificateBuilder(
            /* issuer    */ subject,
            /* serial    */ serial,
            /* notBefore */ now,
            /* notAfter  */ notAfter,
            /* subject   */ subject,
            /* publicKey */ keyPair.public,
        )
        // Subject Alternative Name: the IP we'll be reached at.
        // Chrome requires this for any host that is not a hostname.
        // BC's GeneralName(int, String) constructor parses the IP
        // string into the correct ASN.1 octet-string form.
        val sanGeneralNames = GeneralNames(GeneralName(GeneralName.iPAddress, ip))
        builder.addExtension(Extension.subjectAlternativeName, false, sanGeneralNames)
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun wrapInKeyStore(keyPair: KeyPair, cert: X509Certificate): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keyStore.setKeyEntry(
            ALIAS,
            keyPair.private,
            KEYSTORE_PASSWORD,
            arrayOf(cert),
        )
        return keyStore
    }

    private const val ALIAS = "wyspr-share"
    /**
     * Throwaway password — the keystore lives only in-memory for the
     * lifetime of the share session and never touches disk. Empty
     * passwords trip PKCS12 implementations on some Android versions,
     * so we use a fixed non-empty string.
     */
    private val KEYSTORE_PASSWORD = "wyspr-share-ephemeral".toCharArray()
    /** 24h cert validity — well over any conceivable share session. */
    private const val VALIDITY_MS: Long = 24L * 60L * 60L * 1000L
}
