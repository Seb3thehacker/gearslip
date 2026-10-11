package app.seb3thehacker.gearslip

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Reads APK/SDK data only; never loads classes or executes code from either package. */
internal object ProjectionIdentityExtractor {
    private const val MAX_DEX = 64 * 1024 * 1024
    private const val MAX_SCAN = 256L * 1024 * 1024
    private const val MAX_BLOCK = 32 * 1024
    private val dexName = Regex("classes(?:[0-9]+)?\\.dex")

    fun androidAuto(apks: List<File>): CertProvider.Identity {
        val candidates = DexCandidates()
        var scanned = 0L
        for (apk in apks.distinct()) {
            ZipFile(apk).use { zip ->
                for (entry in zip.entries()) {
                    if (!dexName.matches(entry.name)) continue
                    val bytes = zip.getInputStream(entry).use { readLimited(it, MAX_DEX) }
                    scanned += bytes.size
                    require(scanned <= MAX_SCAN) { "Android Auto's DEX files exceed the scan limit" }
                    candidates.add(bytes)
                    candidates.identity()?.let { return it }
                }
            }
        }
        error("This Android Auto APK does not contain a supported phone identity. Import a .p12 file or select the DHU certificate.")
    }

    /** DHU 2.0's getCert() decodes the PEM blocks with XOR 0x27. Offsets are not fixed. */
    fun desktopHeadUnit(binary: ByteArray): CertProvider.Identity {
        require(binary.size <= 32 * 1024 * 1024) { "DHU executable exceeds the extraction limit" }
        val decoded = ByteArray(binary.size) { (binary[it].toInt() xor 0x27).toByte() }
        val text = decoded.toString(Charsets.ISO_8859_1)
        val certificates = pemBlocks(text, "CERTIFICATE").mapNotNull { pem ->
            runCatching { certificate(pem) }.getOrNull()
        }
        val keys = pemBlocks(text, "PRIVATE KEY")
        for (leaf in certificates) {
            if (!leaf.subjectX500Principal.name.contains("O=Android-Auto-Internal")) continue
            val issuer = certificates.firstOrNull { it.subjectX500Principal == leaf.issuerX500Principal } ?: continue
            if (runCatching { leaf.verify(issuer.publicKey) }.isFailure) continue
            for (key in keys) {
                runCatching {
                    // DHU presents the leaf alone; its root belongs to its peer trust store.
                    identity(listOf(leaf), privateKey(key), "extracted Desktop Head Unit identity", CertProvider.Kind.DHU)
                }.getOrNull()?.let { return it }
            }
        }
        error("The DHU archive does not contain a supported certificate and matching private key")
    }

    private class DexCandidates {
        private val certificates = linkedMapOf<String, X509Certificate>()
        private val salts = mutableListOf<ByteArray>()
        private val encryptedKeys = mutableListOf<ByteArray>()

        fun add(bytes: ByteArray) {
            require(bytes.size >= 112 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) {
                "Invalid DEX header"
            }
            require(uint(bytes, 40) == 0x12345678L) { "Unsupported DEX byte order" }
            require(uint(bytes, 32) == bytes.size.toLong()) { "Truncated DEX file" }
            val count = uint(bytes, 56)
            val table = uint(bytes, 60)
            require(table >= 112 && table + count * 4 <= bytes.size) { "Invalid DEX string table" }
            for (index in 0 until count.toInt()) {
                val offset = uint(bytes, table.toInt() + index * 4)
                require(offset < bytes.size) { "Invalid DEX string offset" }
                var pos = offset.toInt()
                // string_data_item starts with a ULEB128 UTF-16 length. PEM uses only ASCII.
                var ended = false
                repeat(5) {
                    if (!ended) {
                        require(pos < bytes.size) { "Truncated DEX string length" }
                        ended = bytes[pos++].toInt() and 0x80 == 0
                    }
                }
                require(ended) { "Invalid DEX string length" }
                if (!startsWith(bytes, pos, PEM_CERT_HEADER)) continue
                val start = pos
                while (pos < bytes.size && bytes[pos] != 0.toByte() && pos - start <= MAX_BLOCK) pos++
                require(pos < bytes.size && pos - start <= MAX_BLOCK) { "Truncated certificate string" }
                // Keep line endings, including the trailing newline: they feed the key derivation.
                val pem = bytes.copyOfRange(start, pos).toString(Charsets.US_ASCII)
                runCatching { certificate(pem) }.getOrNull()?.let { certificates[pem] = it }
            }
            // DEX fill-array-data payload: ushort 0x0300, ushort width, uint count, data.
            // Class/field names are obfuscated. Validate any candidate by decrypting it and
            // matching its RSA public key to the certificate instead of relying on names.
            for (offset in 0..bytes.size - 8 step 4) {
                if (uint(bytes, offset) != 0x00010300L) continue
                val size = uint(bytes, offset + 4)
                if (offset + 8L + size > bytes.size) continue
                val destination = when {
                    size == 256L -> salts
                    size in 1024L..8192L && size % 16 == 0L -> encryptedKeys
                    else -> continue
                }
                val data = bytes.copyOfRange(offset + 8, offset + 8 + size.toInt())
                if (destination.none { it.contentEquals(data) }) destination += data
            }
            require(certificates.size <= 32 && salts.size <= 64 && encryptedKeys.size <= 64) {
                "Too many Android Auto identity candidates"
            }
        }

        fun identity(): CertProvider.Identity? {
            for ((leafPem, leaf) in certificates) {
                if (!leaf.subjectX500Principal.name.contains("O=CarService")) continue
                for ((rootPem, root) in certificates) {
                    if (leaf.issuerX500Principal != root.subjectX500Principal) continue
                    if (runCatching { leaf.verify(root.publicKey) }.isFailure) continue
                    for (salt in salts) {
                        val state = derive(leafPem, rootPem, salt)
                        for (encrypted in encryptedKeys) {
                            runCatching {
                                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(state, 0, 32, "AES"), IvParameterSpec(state, 32, 16))
                                val pem = cipher.doFinal(encrypted).toString(Charsets.US_ASCII)
                                identity(listOf(leaf, root), privateKey(pem), "extracted Android Auto phone identity", CertProvider.Kind.ANDROID_AUTO)
                            }.getOrNull()?.let { return it }
                        }
                    }
                }
            }
            return null
        }
    }

    /** Matches the APK's bytecode, including input/output aliasing in the seven final rounds. */
    internal fun derive(leaf: String, root: String, salt: ByteArray): ByteArray {
        require(salt.isNotEmpty())
        val state = ByteArray(48)
        fun mix(input: ByteArray) {
            for (j in input.indices) for (i in state.indices) {
                val value = state[i].toInt() and 255
                // Do not hoist input[j] out of this loop: input can be state itself.
                state[i] = (((((value shl 1) or (value shr 7)) + 33) xor salt[i % salt.size].toInt()) xor input[j].toInt()).toByte()
            }
        }
        mix(leaf.toByteArray(Charsets.UTF_8))
        mix(root.toByteArray(Charsets.UTF_8))
        repeat(7) { mix(state) }
        return state
    }

    internal fun identity(chain: List<X509Certificate>, key: RSAPrivateKey, source: String, kind: CertProvider.Kind): CertProvider.Identity {
        require(chain.isNotEmpty()) { "Empty certificate chain" }
        val publicKey = chain.first().publicKey as? RSAPublicKey ?: error("Certificate is not RSA")
        require(key.modulus == publicKey.modulus) { "Certificate and private key do not match" }
        // Some Android providers expose only RSAPrivateKey, even for a PKCS#8 CRT key.
        // A signature proves the pair matches without requiring provider-specific key details.
        val proof = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(chain.first().encoded)
            sign()
        }
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(chain.first().encoded)
            verify(proof)
        }) { "Certificate and private key do not match" }
        chain.zipWithNext().forEach { (child, parent) ->
            require(child.issuerX500Principal == parent.subjectX500Principal) { "Certificate chain is out of order" }
            child.verify(parent.publicKey)
        }
        val password = CharArray(0)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("projection", key, password, chain.toTypedArray())
        }
        return CertProvider.Identity(store, chain.first(), password, source, kind)
    }

    private fun privateKey(pem: String): RSAPrivateKey {
        val block = pemBlocks(pem, "PRIVATE KEY").single()
        val encoded = block.removePrefix("-----BEGIN PRIVATE KEY-----").removeSuffix("-----END PRIVATE KEY-----").filterNot { it.isWhitespace() }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded))) as RSAPrivateKey
    }

    private fun certificate(pem: String) = CertificateFactory.getInstance("X.509")
        .generateCertificate(pem.byteInputStream(Charsets.US_ASCII)) as X509Certificate

    private fun pemBlocks(text: String, type: String): List<String> {
        val header = "-----BEGIN $type-----"
        val footer = "-----END $type-----"
        val blocks = mutableListOf<String>()
        var start = text.indexOf(header)
        while (start >= 0 && blocks.size < 32) {
            val end = text.indexOf(footer, start + header.length)
            if (end >= 0 && end - start <= MAX_BLOCK) blocks += text.substring(start, end + footer.length)
            start = text.indexOf(header, start + header.length)
        }
        return blocks
    }

    internal fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size().toLong() + read <= limit) { "Certificate source exceeds the size limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun startsWith(bytes: ByteArray, offset: Int, prefix: ByteArray) =
        offset + prefix.size <= bytes.size && prefix.indices.all { bytes[offset + it] == prefix[it] }

    private fun uint(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset <= bytes.size - 4) { "Truncated DEX structure" }
        return (bytes[offset].toLong() and 255) or
            ((bytes[offset + 1].toLong() and 255) shl 8) or
            ((bytes[offset + 2].toLong() and 255) shl 16) or
            ((bytes[offset + 3].toLong() and 255) shl 24)
    }

    private val PEM_CERT_HEADER = "-----BEGIN CERTIFICATE-----".toByteArray(Charsets.US_ASCII)
}
