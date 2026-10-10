package app.seb3thehacker.gearslip

import android.content.Context
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Bundled projection identities, with the Android Auto phone identity preferred.
 *
 * The shared CarService TLS identity is unrelated to either app's APK signing key. Its
 * chain includes the Google Automotive Link root, leaf-first as the official app sends it.
 * Try CarService even after its 2027-01-20 expiry: some head units may still accept it.
 * The DHU 2.0 identity (expires 2048-08-01) is reserved for a failed attempt or debugging.
 * The DHU sends only its leaf; its root is used separately to verify its peer.
 */
object CertProvider {
    enum class Source(val statsKey: String, val description: String) {
        ANDROID_AUTO("android_auto", "bundled Android Auto 17.9.664004 projection identity"),
        HEAD_UNIT("dhu", "bundled DHU 2.0 fallback identity"),
    }

    class Identity(
        val keyStore: KeyStore,
        val certificate: X509Certificate,
        val password: CharArray,
        val source: String,
        val statsKey: String = "other",
    )

    private val cached = mutableMapOf<Int, Identity>()

    /** Expiry is informational; let the head unit decide whether to accept this identity. */
    fun load(context: Context, source: Source = Source.ANDROID_AUTO): Identity = when (source) {
        Source.ANDROID_AUTO -> loadBundled(context, R.raw.projection_chain, R.raw.projection_key, source)
        Source.HEAD_UNIT -> loadBundled(context, R.raw.projection_fallback, R.raw.projection_fallback_key, source)
    }

    /** Both identities are immutable resources; parse each at most once per process. */
    @Synchronized
    private fun loadBundled(context: Context, certificate: Int, key: Int, source: Source): Identity =
        cached.getOrPut(certificate) {
            readIdentity(
                context.resources.openRawResource(certificate).use { it.readBytes() },
                context.resources.openRawResource(key).use { it.readBytes() },
                source.description, source.statsKey,
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
        source: String = Source.ANDROID_AUTO.description,
        statsKey: String = Source.ANDROID_AUTO.statsKey,
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
        return Identity(keyStore, chain.first(), password, source, statsKey)
    }
}
