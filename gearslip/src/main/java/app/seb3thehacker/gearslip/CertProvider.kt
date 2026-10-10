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
 * The phone-side projection identity bundled in Android Auto 17.9.664004.
 *
 * This is the shared CarService TLS identity, unrelated to either app's APK signing key.
 * Its leaf expires on 2027-01-20; replacing it requires an app update. The chain includes
 * the Google Automotive Link root, in the same leaf-first order the official app sends.
 */
object CertProvider {
    class Identity(
        val keyStore: KeyStore,
        val certificate: X509Certificate,
        val password: CharArray,
        val source: String,
    )

    @Volatile private var cached: Identity? = null

    /** Immutable packaged resources cannot change within a process, so unlock only once. */
    fun load(context: Context): Identity = cached ?: synchronized(this) {
        cached ?: readIdentity(
            context.resources.openRawResource(R.raw.projection_chain).use { it.readBytes() },
            context.resources.openRawResource(R.raw.projection_key).use { it.readBytes() },
        ).also { cached = it }
    }

    /** Keep certificate parsing off the head unit's first-handshake deadline. */
    fun warm(context: Context) {
        runCatching { load(context) }
            .onFailure { GearslipLog.e("could not load the bundled projection identity", it) }
    }

    internal fun readIdentity(chainBytes: ByteArray, keyBytes: ByteArray): Identity {
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
        return Identity(keyStore, chain.first(), password, "bundled Android Auto 17.9.664004 projection identity")
    }
}
