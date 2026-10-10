package app.seb3thehacker.gearslip

import android.content.Context
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Bundled projection identities, with the Android Auto phone identity preferred.
 *
 * The shared CarService TLS identity is unrelated to either app's APK signing key. Its
 * chain includes the Google Automotive Link root, leaf-first as the official app sends it.
 * After its 2027-01-20 expiry, new sessions use the DHU 2.0 identity (expires 2048-08-01).
 * The DHU sends only its leaf; its root is used separately to verify its peer.
 */
object CertProvider {
    class Identity(
        val keyStore: KeyStore,
        val certificate: X509Certificate,
        val password: CharArray,
        val source: String,
    )

    private val cached = mutableMapOf<Int, Identity>()

    /** Recheck the date for every session, even when the identities were already warmed. */
    fun load(context: Context): Identity {
        val primary = loadBundled(
            context, R.raw.projection_chain, R.raw.projection_key,
            "bundled Android Auto 17.9.664004 projection identity",
        )
        return selectIdentity(primary, Date()) {
            loadBundled(
                context, R.raw.projection_fallback, R.raw.projection_fallback_key,
                "bundled DHU 2.0 fallback identity",
            )
        }
    }

    /** Expiry alone enables the fallback; loading errors and head-unit rejection do not. */
    internal fun selectIdentity(primary: Identity, now: Date, fallback: () -> Identity): Identity {
        if (!now.after(primary.certificate.notAfter)) return primary
        return fallback().also { it.certificate.checkValidity(now) }
    }

    /** Cache parsed resources, not the date-dependent choice of identity. */
    @Synchronized
    private fun loadBundled(context: Context, certificate: Int, key: Int, source: String): Identity =
        cached.getOrPut(certificate) {
            readIdentity(
                context.resources.openRawResource(certificate).use { it.readBytes() },
                context.resources.openRawResource(key).use { it.readBytes() },
                source,
            )
        }

    /** Keep certificate parsing off the head unit's first-handshake deadline. */
    fun warm(context: Context) {
        runCatching { load(context) }
            .onFailure { GearslipLog.e("could not load the bundled projection identity", it) }
    }

    internal fun readIdentity(
        chainBytes: ByteArray,
        keyBytes: ByteArray,
        source: String = "bundled Android Auto 17.9.664004 projection identity",
    ): Identity {
        val chain = CertificateFactory.getInstance("X.509")
            .generateCertificates(chainBytes.inputStream()).map { it as X509Certificate }
        require(chain.isNotEmpty()) { "the bundled certificate chain is empty" }
        val key = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes)) as RSAPrivateCrtKey
        val publicKey = chain.first().publicKey as RSAPublicKey
        require(key.modulus == publicKey.modulus && key.publicExponent == publicKey.publicExponent) {
            "the bundled certificate and private key do not match"
        }
        chain.zipWithNext().forEach { (certificate, issuer) ->
            require(certificate.issuerX500Principal == issuer.subjectX500Principal) {
                "the bundled certificate chain is out of order"
            }
            certificate.verify(issuer.publicKey)
        }
        // An APK resource is public; a password here would not protect this shared identity.
        val password = CharArray(0)
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("projection", key, password, chain.toTypedArray())
        }
        return Identity(keyStore, chain.first(), password, source)
    }
}
