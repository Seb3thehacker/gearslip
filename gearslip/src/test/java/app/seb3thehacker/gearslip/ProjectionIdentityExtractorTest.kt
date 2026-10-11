package app.seb3thehacker.gearslip

import java.io.File
import java.security.interfaces.RSAPrivateKey
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectionIdentityExtractorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `key derivation preserves aliased reads in the final seven rounds`() {
        val state = ProjectionIdentityExtractor.derive("phone\n", "root\n", ByteArray(256) { it.toByte() })
        assertEquals("b850337e80bb40d09f513da69c6606612ee0a949544db79674aec86c1bfbaadcd565de00ba7b5570cb58118c6a80d999",
            state.joinToString("") { "%02x".format(it) })
    }

    @Test fun `supports Android providers that do not expose CRT key parameters`() {
        val sample = ProjectionIdentityFixture()
        val original = sample.identity.keyStore.getKey("projection", CharArray(0)) as RSAPrivateKey
        val basicKey: RSAPrivateKey = object : RSAPrivateKey by original {}
        assertFalse(basicKey is java.security.interfaces.RSAPrivateCrtKey)
        val identity = ProjectionIdentityExtractor.identity(listOf(sample.phone, sample.root), basicKey, "test", CertProvider.Kind.ANDROID_AUTO)
        assertTrue(TlsSelfTest.run(identity))
    }

    @Test fun `rejects a private key from a different identity`() {
        val sample = ProjectionIdentityFixture()
        val other = ProjectionIdentityFixture().identity.keyStore.getKey("projection", CharArray(0)) as RSAPrivateKey
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionIdentityExtractor.identity(listOf(sample.phone), other, "test", CertProvider.Kind.ANDROID_AUTO)
        }
    }

    private fun apk(vararg entries: Pair<String, ByteArray>): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }

    @Test fun `extracts a phone chain and key from multidex in an APK split`() {
        val sample = ProjectionIdentityFixture()
        val identity = ProjectionIdentityExtractor.androidAuto(listOf(apk("assets/readme" to byteArrayOf(1)), apk("classes2.dex" to sample.dex())))
        assertEquals(sample.phone, identity.certificate)
        assertEquals(listOf(sample.phone, sample.root), identity.keyStore.getCertificateChain("projection").toList())
        assertTrue(TlsSelfTest.run(identity))
    }

    @Test fun `DEX offsets and certificate locations are not hardcoded`() {
        val sample = ProjectionIdentityFixture()
        val dex = sample.dex()
        assertEquals(sample.phone, ProjectionIdentityExtractor.androidAuto(listOf(apk("classes37.dex" to dex))).certificate)
    }

    @Test fun `corrupt encrypted material cannot produce an identity`() {
        val sample = ProjectionIdentityFixture()
        assertThrows(IllegalStateException::class.java) {
            ProjectionIdentityExtractor.androidAuto(listOf(apk("classes.dex" to sample.dex("not a private key".repeat(100)))))
        }
    }

    @Test fun `truncated or invalid DEX is rejected`() {
        val sample = ProjectionIdentityFixture()
        for (data in listOf(sample.dex().copyOf(140), ByteArray(16), sample.dex().also { it[60] = -1; it[61] = -1; it[62] = -1; it[63] = 127 })) {
            assertThrows(IllegalArgumentException::class.java) { ProjectionIdentityExtractor.androidAuto(listOf(apk("classes.dex" to data))) }
        }
    }

    @Test fun `DHU extraction verifies the issuer and keeps the leaf-only chain`() {
        val sample = ProjectionIdentityFixture()
        val identity = ProjectionIdentityExtractor.desktopHeadUnit(sample.dhu())
        assertEquals(sample.headUnit, identity.certificate)
        assertEquals(1, identity.keyStore.getCertificateChain("projection").size)
        assertTrue(TlsSelfTest.run(identity))
    }

    @Test fun `wrong DHU archive checksum and missing blocks are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { DhuCertificateDownloader.extract(byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalStateException::class.java) { ProjectionIdentityExtractor.desktopHeadUnit(ByteArray(256)) }
    }

    @Test fun `bounded reads reject oversized input`() {
        assertThrows(IllegalArgumentException::class.java) { ProjectionIdentityExtractor.readLimited(ByteArray(10).inputStream(), 9) }
        assertEquals(10, ProjectionIdentityExtractor.readLimited(ByteArray(10).inputStream(), 10).size)
    }

    @Test fun `DHU is fetched only once across setup retries and process restarts`() {
        val directory = temporary.newFolder()
        val sample = ProjectionIdentityFixture()
        val identity = ProjectionIdentityExtractor.desktopHeadUnit(sample.dhu())
        var fetches = 0
        repeat(3) {
            val stored = ProjectionIdentityStore(directory).getOrCreate(CertProvider.Source.HEAD_UNIT) { fetches++; identity }
            assertEquals(identity.certificate, stored.certificate)
        }
        assertEquals(1, fetches)
    }

    @Test fun `failed setup is retryable and successful refresh replaces the complete identity`() {
        val storage = ProjectionIdentityStore(temporary.newFolder())
        assertThrows(IllegalStateException::class.java) {
            storage.getOrCreate(CertProvider.Source.ANDROID_AUTO) { error("download or extraction failed") }
        }
        assertNull(storage.read(CertProvider.Source.ANDROID_AUTO))
        val first = ProjectionIdentityFixture().identity
        storage.write(CertProvider.Source.ANDROID_AUTO, first)
        assertThrows(IllegalStateException::class.java) {
            // Extraction fails before a replacement can be written.
            ProjectionIdentityExtractor.androidAuto(listOf(apk("assets/no-identity" to byteArrayOf(1))))
        }
        assertEquals(first.certificate, storage.read(CertProvider.Source.ANDROID_AUTO)!!.certificate)
        val updated = ProjectionIdentityFixture().identity
        storage.write(CertProvider.Source.ANDROID_AUTO, updated)
        val restored = storage.read(CertProvider.Source.ANDROID_AUTO)!!
        assertEquals(updated.certificate, restored.certificate)
        assertTrue(TlsSelfTest.run(restored))
    }
}
