package app.seb3thehacker.gearslip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.util.Base64
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generates every test identity in memory; no certificate or key fixtures are bundled. */
class PhoneCertificateImportTest {
    private val rootKey = keyPair()
    private val phoneKey = keyPair()
    private val root = certificate(rootKey, "Test root", rootKey, "Test root")
    private val phone = certificate(phoneKey, "Test phone", rootKey, "Test root")
    private val chain = pem("CERTIFICATE", phone.encoded) + pem("CERTIFICATE", root.encoded)

    @Test fun `binary PKCS8 import preserves the chain through storage and TLS`() {
        val imported = PemKeyParser.build(chain, phoneKey.private.encoded)
        val bytes = ByteArrayOutputStream().also { imported.keyStore.store(it, CharArray(0)) }.toByteArray()
        val restored = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(bytes), CharArray(0)) }
        assertEquals(listOf(phone, root), restored.getCertificateChain("phone").toList())
        assertArrayEquals(phoneKey.private.encoded, restored.getKey("phone", CharArray(0)).encoded)
        assertTrue(TlsSelfTest.run(CertProvider.Identity(restored, phone, CharArray(0), "test", CertProvider.Kind.IMPORTED)))
    }

    @Test fun `accepts a PEM PKCS8 key`() {
        val imported = PemKeyParser.build(chain, pem("PRIVATE KEY", phoneKey.private.encoded))
        assertEquals(phone, imported.certificate)
        assertEquals(2, imported.keyStore.getCertificateChain("phone").size)
    }

    @Test fun `existing single certificate and PKCS1 download format still works`() {
        val key = phoneKey.private as RSAPrivateCrtKey
        val pkcs1 = seq(*listOf(
            BigInteger.ZERO, key.modulus, key.publicExponent, key.privateExponent,
            key.primeP, key.primeQ, key.primeExponentP, key.primeExponentQ, key.crtCoefficient,
        ).map { tlv(2, it.toByteArray()) }.toTypedArray())
        val imported = PemKeyParser.build(pem("CERTIFICATE", phone.encoded), pem("RSA PRIVATE KEY", pkcs1))
        assertEquals(phone, imported.certificate)
        assertArrayEquals(phoneKey.private.encoded, imported.keyStore.getKey("phone", CharArray(0)).encoded)
    }

    @Test fun `rejects a mismatched private key`() {
        assertThrows(IllegalArgumentException::class.java) { PemKeyParser.build(chain, rootKey.private.encoded) }
    }

    @Test fun `rejects a chain with the root before the phone`() {
        assertThrows(IllegalArgumentException::class.java) {
            PemKeyParser.build(pem("CERTIFICATE", root.encoded) + pem("CERTIFICATE", phone.encoded), phoneKey.private.encoded)
        }
    }

    @Test fun `rejects an unrelated issuer even when its name matches`() {
        val impostor = certificate(keyPair(), "Test root", rootKey, "Test root")
        assertThrows(java.security.SignatureException::class.java) {
            PemKeyParser.build(pem("CERTIFICATE", phone.encoded) + pem("CERTIFICATE", impostor.encoded), phoneKey.private.encoded)
        }
    }

    @Test fun `rejects malformed certificates and keys`() {
        assertThrows(java.security.cert.CertificateException::class.java) {
            PemKeyParser.build("not a certificate", phoneKey.private.encoded)
        }
        assertThrows(java.security.spec.InvalidKeySpecException::class.java) {
            PemKeyParser.build(chain, byteArrayOf(1, 2, 3))
        }
    }

    @Test fun `encrypted PEM keys direct users to the existing PKCS12 import`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PemKeyParser.build(chain, pem("ENCRYPTED PRIVATE KEY", byteArrayOf(1, 2, 3)))
        }
        assertTrue(error.message!!.contains(".p12"))
    }

    private fun keyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun pem(type: String, bytes: ByteArray) =
        "-----BEGIN $type-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes)}\n-----END $type-----\n"

    private fun certificate(subject: KeyPair, subjectName: String, issuer: KeyPair, issuerName: String): X509Certificate {
        val algorithm = seq(byteArrayOf(6, 9, 42, -122, 72, -122, -9, 13, 1, 1, 11), tlv(5, byteArrayOf()))
        val tbs = seq(
            tlv(0xA0, tlv(2, byteArrayOf(2))), tlv(2, byteArrayOf(1)), algorithm,
            X500Principal("CN=$issuerName").encoded,
            seq(tlv(0x17, "240101000000Z".toByteArray()), tlv(0x17, "490101000000Z".toByteArray())),
            X500Principal("CN=$subjectName").encoded, subject.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(issuer.private); update(tbs); sign()
        }
        return CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(seq(tbs, algorithm, tlv(3, byteArrayOf(0) + signature))),
        ) as X509Certificate
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(byteArrayOf()) { a, b -> a + b })
    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val length = when {
            body.size < 128 -> byteArrayOf(body.size.toByte())
            body.size < 256 -> byteArrayOf(0x81.toByte(), body.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + body
    }
}
