package app.seb3thehacker.gearslip

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Optional integration tests against external downloads; no third-party key material is committed. */
class ProjectionReferenceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun reference(property: String): File {
        val path = System.getProperty(property).orEmpty()
        assumeTrue("Supply $property to test the external reference", path.isNotBlank())
        return File(path).also { require(it.isFile) { "Missing reference: $path" } }
    }

    private fun fingerprint(identity: CertProvider.Identity) = MessageDigest.getInstance("SHA-256")
        .digest(identity.certificate.encoded).joinToString("") { "%02x".format(it) }

    @Test fun `Android Auto 17_9 reference APKM yields the phone identity and completes TLS`() {
        val apkm = reference("gearslip.referenceApkm")
        val apks = ZipFile(apkm).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".apk") }.map { entry ->
                temporary.newFile().also { file -> zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } } }
            }.toList()
        }
        val identity = ProjectionIdentityExtractor.androidAuto(apks)
        assertEquals("39b7417be3f2bcd60b30e3acd4a2995d82661d6d66110e45c10a15d2a3c2ee6e", fingerprint(identity))
        assertEquals(2, identity.keyStore.getCertificateChain("projection").size)
        assertTrue(TlsSelfTest.run(identity))
    }

    @Test fun `official DHU 2_0 archive yields the head unit identity and completes TLS`() {
        val archive = reference("gearslip.referenceDhu")
        val identity = DhuCertificateDownloader.extract(archive.readBytes())
        assertEquals("4eb581dcee2b84369ca87066ab6eaa73a4783aef5c7b6edc6841e066cffa7e7c", fingerprint(identity))
        assertEquals(1, identity.keyStore.getCertificateChain("projection").size)
        assertTrue(TlsSelfTest.run(identity))
    }
}
