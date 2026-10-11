package app.seb3thehacker.gearslip

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/** Every test identity is generated at runtime, including the obfuscated DEX/DHU samples. */
internal class ProjectionIdentityFixture {
    private val rootKey = keyPair()
    private val phoneKey = keyPair()
    val root = certificate(rootKey, "Google Automotive Link", rootKey, "Google Automotive Link")
    val phone = certificate(phoneKey, "CarService", rootKey, "Google Automotive Link")
    val headUnit = certificate(phoneKey, "Android-Auto-Internal", rootKey, "Google Automotive Link")
    val leafPem = pem("CERTIFICATE", phone.encoded)
    val rootPem = pem("CERTIFICATE", root.encoded)
    val keyPem = pem("PRIVATE KEY", phoneKey.private.encoded)
    val salt = ByteArray(256) { (it * 19 + 7).toByte() }
    val identity = ProjectionIdentityExtractor.identity(listOf(phone, root), phoneKey.private as java.security.interfaces.RSAPrivateCrtKey,
        CertProvider.Source.ANDROID_AUTO.description, CertProvider.Kind.ANDROID_AUTO)

    fun dex(key: String = keyPem): ByteArray {
        val state = ProjectionIdentityExtractor.derive(leafPem, rootPem, salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(state, 0, 32, "AES"), IvParameterSpec(state, 32, 16))
        val encrypted = cipher.doFinal(key.toByteArray())
        val output = ByteArrayOutputStream()
        output.write(ByteArray(120)) // DEX header plus two string IDs
        val offsets = listOf(leafPem, rootPem).map { text ->
            val offset = output.size()
            var length = text.length
            do { output.write((length and 127) or if (length > 127) 128 else 0); length = length ushr 7 } while (length > 0)
            output.write(text.toByteArray()); output.write(0)
            offset
        }
        for (array in listOf(salt, encrypted)) {
            while (output.size() % 4 != 0) output.write(0)
            output.write(byteArrayOf(0, 3, 1, 0))
            output.write(le(array.size)); output.write(array)
        }
        return output.toByteArray().apply {
            "dex\n035\u0000".toByteArray().copyInto(this)
            le(size).copyInto(this, 32)
            le(112).copyInto(this, 36)
            le(0x12345678).copyInto(this, 40)
            le(2).copyInto(this, 56)
            le(112).copyInto(this, 60)
            offsets.forEachIndexed { index, offset -> le(offset).copyInto(this, 112 + index * 4) }
        }
    }

    fun dhu(): ByteArray = ("padding\u0000" + rootPem + "\u0000" + pem("CERTIFICATE", headUnit.encoded) + "\u0000" + keyPem)
        .toByteArray().map { (it.toInt() xor 0x27).toByte() }.toByteArray()

    private fun keyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun pem(type: String, bytes: ByteArray) =
        "-----BEGIN $type-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes)}\n-----END $type-----\n"
    private fun le(value: Int) = ByteArray(4) { (value ushr (8 * it)).toByte() }

    private fun certificate(subject: KeyPair, name: String, issuer: KeyPair, issuerName: String): X509Certificate {
        val algorithm = seq(byteArrayOf(6, 9, 42, -122, 72, -122, -9, 13, 1, 1, 11), tlv(5, byteArrayOf()))
        val tbs = seq(tlv(0xA0, tlv(2, byteArrayOf(2))), tlv(2, byteArrayOf(1)), algorithm,
            X500Principal("O=$issuerName").encoded,
            seq(tlv(0x17, "240101000000Z".toByteArray()), tlv(0x17, "490101000000Z".toByteArray())),
            X500Principal("O=$name").encoded, subject.public.encoded)
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(issuer.private); update(tbs); sign() }
        return CertificateFactory.getInstance("X.509").generateCertificate(
            seq(tbs, algorithm, tlv(3, byteArrayOf(0) + signature)).inputStream()) as X509Certificate
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
