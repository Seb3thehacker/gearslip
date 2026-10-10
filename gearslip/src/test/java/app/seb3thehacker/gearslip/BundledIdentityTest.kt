package app.seb3thehacker.gearslip

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verify the packaged files themselves, not a duplicate certificate embedded in the test. */
class BundledIdentityTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)).use { it.readBytes() }

    private fun identity() = CertProvider.readIdentity(resource("projection_chain.pem"), resource("projection_key.pk8"))

    @Test fun `bundled chain is the official phone identity and has its matching key`() {
        val identity = identity()
        val leaf = identity.certificate
        assertEquals("O=CarService,L=Mountain View,ST=California,C=US", leaf.subjectX500Principal.name)
        assertEquals("39b7417be3f2bcd60b30e3acd4a2995d82661d6d66110e45c10a15d2a3c2ee6e", sha256(leaf))
        assertEquals(Instant.parse("2027-01-20T22:48:17Z"), leaf.notAfter.toInstant())

        val chain = identity.keyStore.getCertificateChain("projection")
        assertEquals(2, chain.size)
        val root = chain.last() as X509Certificate
        assertEquals("49e52efc13ad2ed09f204c3b10698bd84bb7105f510558aa14b8119a5c4ad17f", sha256(root))
        leaf.verify(root.publicKey)
        root.verify(root.publicKey)
        assertEquals("RSA", identity.keyStore.getKey("projection", identity.password).algorithm)
    }

    @Test fun `bundled identity completes TLS and encrypts projection data`() {
        assertTrue(TlsSelfTest.run(identity()))
    }

    @Test fun `a mismatched private key is rejected before starting TLS`() {
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        assertThrows(IllegalArgumentException::class.java) {
            CertProvider.readIdentity(resource("projection_chain.pem"), otherKey.encoded)
        }
    }

    @Test fun `a substituted issuer is rejected before starting TLS`() {
        val leaf = CertificateFactory.getInstance("X.509")
            .generateCertificate(resource("projection_chain.pem").inputStream())
        val wrongIssuer = SelfSignedCert.generate().certificate
        assertThrows(IllegalArgumentException::class.java) {
            CertProvider.readIdentity(leaf.encoded + wrongIssuer.encoded, resource("projection_key.pk8"))
        }
    }

    @Test fun `an empty chain cannot silently fall back to a generated identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            CertProvider.readIdentity(byteArrayOf(), resource("projection_key.pk8"))
        }
    }

    private fun sha256(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
}
