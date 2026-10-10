package app.seb3thehacker.gearslip

import android.content.ContextWrapper
import android.content.res.Resources
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Existing user-supplied sources must keep precedence over the bundled default. */
class CertificateSourcesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun resource(name: String) = requireNotNull(javaClass.classLoader!!.getResourceAsStream(name))

    private fun context(): ContextWrapper {
        val files = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        val external = temporary.newFolder("external")
        val resources = object : Resources(null, null, null) {
            override fun openRawResource(id: Int) = resource(when (id) {
                R.raw.projection_chain -> "projection_chain.pem"
                R.raw.projection_key -> "projection_key.pk8"
                R.raw.projection_fallback -> "projection_fallback.pem"
                R.raw.projection_fallback_key -> "projection_fallback_key.pk8"
                else -> error("unexpected resource")
            })
        }
        return object : ContextWrapper(null) {
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
            override fun getExternalFilesDir(type: String?) = external
            override fun getResources() = resources
        }
    }

    @Test fun `existing PKCS12 import accepts the phone chain and overrides either bundled choice`() {
        val context = context()
        val phone = CertProvider.readIdentity(
            resource("projection_chain.pem").use { it.readBytes() },
            resource("projection_key.pk8").use { it.readBytes() },
        )
        val bytes = ByteArrayOutputStream().also { phone.keyStore.store(it, phone.password) }.toByteArray()
        CertProvider.importFromBytes(context, bytes, "")
        for (source in CertProvider.Source.entries) {
            val loaded = CertProvider.load(context, source)
            assertEquals(CertProvider.Kind.IMPORTED, loaded.kind)
            assertEquals(phone.certificate, loaded.certificate)
            assertEquals(2, loaded.keyStore.getCertificateChain("projection").size)
        }
        assertThrows(Exception::class.java) { CertProvider.importFromBytes(context, bytes, "wrong password") }
        assertEquals(CertProvider.Kind.IMPORTED, CertProvider.load(context, CertProvider.Source.HEAD_UNIT).kind)
        CertProvider.removeImported(context)
        val fallback = CertProvider.load(context, CertProvider.Source.HEAD_UNIT)
        assertEquals(CertProvider.Kind.BUNDLED, fallback.kind)
        assertEquals(CertProvider.Source.HEAD_UNIT.description, fallback.source)
    }

    @Test fun `downloaded and staged certificates retain their original priority`() {
        val context = context()
        val generated = SelfSignedCert.generate()
        val bytes = ByteArrayOutputStream().also { generated.keyStore.store(it, SelfSignedCert.PASSWORD) }.toByteArray()
        val downloaded = File(context.filesDir, "identity-downloaded/phone.p12").apply { parentFile!!.mkdirs() }
        // The downloaded source uses the original fixed password.
        val downloadStore = generated.keyStore.apply {
            setKeyEntry(SelfSignedCert.ALIAS, getKey(SelfSignedCert.ALIAS, SelfSignedCert.PASSWORD),
                "aaspike".toCharArray(), getCertificateChain(SelfSignedCert.ALIAS))
        }
        downloaded.outputStream().use { downloadStore.store(it, "aaspike".toCharArray()) }
        val staged = File(context.getExternalFilesDir(null), "phone.p12").apply { writeBytes(bytes) }
        File(context.getExternalFilesDir(null), "phone.pass").writeText(String(SelfSignedCert.PASSWORD))
        assertEquals(CertProvider.Kind.DOWNLOADED, CertProvider.load(context, CertProvider.Source.ANDROID_AUTO).kind)
        CertProvider.removeDownloaded(context)
        assertEquals(CertProvider.Kind.ADB_STAGED, CertProvider.load(context, CertProvider.Source.ANDROID_AUTO).kind)
        staged.delete()
        assertEquals(CertProvider.Kind.BUNDLED, CertProvider.load(context, CertProvider.Source.ANDROID_AUTO).kind)
    }
}
