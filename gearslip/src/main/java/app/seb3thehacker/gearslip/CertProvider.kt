package app.seb3thehacker.gearslip

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Supplies the certificate the phone presents to the head unit.
 *
 * FOSS posture: this code ships with NO certificate. Whoever wants to test the trusted-cert
 * path supplies their own. Sources, in priority order:
 *
 *  1. **Imported** in the app (Settings > Certificate): a PKCS#12 file plus its password,
 *     copied into the app's private storage.
 *  2. **Staged over adb** into the app's external files directory, the way the spike has
 *     always done it. Still honoured, so existing setups keep working untouched:
 *
 *       openssl pkcs12 -export -in headunit.crt -inkey headunit.key \
 *           -name phone -passout pass:aaspike -out phone.p12
 *       adb push phone.p12 /sdcard/Android/data/app.seb3thehacker.gearslip/files/phone.p12
 *
 *     Default password is "aaspike" (kept as-is so an already-staged phone.p12 doesn't need
 *     regenerating); a `phone.pass` file next to it overrides it.
 *  3. A freshly generated self-signed cert, which a real head unit rejects (see
 *     SPIKE_FINDINGS.md).
 */
object CertProvider {
    private val log = GearslipLog.tagged("CERT")

    private const val P12_NAME = "phone.p12"
    private const val PASS_NAME = "phone.pass"
    private const val DEFAULT_PASSWORD = "aaspike"
    private const val IMPORT_DIR = "identity"

    enum class Kind { IMPORTED, ADB_STAGED, SELF_SIGNED }

    class Identity(
        val keyStore: KeyStore,
        val certificate: X509Certificate,
        val password: CharArray,
        val source: String,
        val kind: Kind = Kind.SELF_SIGNED,
    )

    /** Loaded fresh each call, so a newly imported or pushed cert takes effect on the next connect. */
    fun load(context: Context): Identity = loadFromFiles(context) ?: run {
        val generated = SelfSignedCert.generate()
        Identity(
            generated.keyStore,
            generated.certificate,
            SelfSignedCert.PASSWORD,
            "self-signed (generated) - a real head unit rejects this",
            Kind.SELF_SIGNED,
        )
    }

    /**
     * The active identity without the cost of generating a self-signed key: null means "nothing
     * supplied, a self-signed one would be generated". For the UI, which only wants to describe it.
     */
    fun loadSupplied(context: Context): Identity? = loadFromFiles(context)

    private fun loadFromFiles(context: Context): Identity? {
        val imported = importedFile(context, P12_NAME)
        if (imported.isFile) {
            runCatching {
                return loadP12(imported, importedPassword(context), "imported certificate", Kind.IMPORTED)
            }.onFailure { log.e("could not load the imported certificate - trying the next source", it) }
        }

        val dir = context.getExternalFilesDir(null)
        val staged = dir?.let { File(it, P12_NAME) }
        if (dir != null && staged != null && staged.isFile) {
            runCatching {
                val passFile = File(dir, PASS_NAME)
                val password = if (passFile.isFile) {
                    passFile.readText().lineSequence().firstOrNull().orEmpty().trim()
                } else {
                    DEFAULT_PASSWORD
                }
                return loadP12(staged, password, "$P12_NAME staged over adb", Kind.ADB_STAGED)
            }.onFailure { log.e("could not load $P12_NAME - falling back to self-signed", it) }
        }
        return null
    }

    /**
     * Validates [uri] as a PKCS#12 identity with [password] and, only if it is good, makes it
     * the active certificate. A wrong password or a file with no private key throws and
     * leaves whatever was active before untouched.
     */
    fun importFrom(context: Context, uri: Uri, password: String): Identity {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("could not open the selected file")

        val staging = File(context.cacheDir, "import-$P12_NAME")
        try {
            staging.writeBytes(bytes)
            val identity = loadP12(staging, password, "imported certificate", Kind.IMPORTED)

            val target = importedFile(context, P12_NAME)
            target.parentFile?.mkdirs()
            staging.copyTo(target, overwrite = true)
            importedFile(context, PASS_NAME).writeText(password)
            log.i("imported a certificate: ${identity.certificate.subjectX500Principal}")
            return identity
        } finally {
            staging.delete()
        }
    }

    fun hasImported(context: Context): Boolean = importedFile(context, P12_NAME).isFile

    /** Drops the imported identity; the next source in line (adb-staged, then self-signed) takes over. */
    fun removeImported(context: Context) {
        importedFile(context, P12_NAME).delete()
        importedFile(context, PASS_NAME).delete()
        log.i("removed the imported certificate")
    }

    // The imported files live in the app's private internal storage (not the external files
    // dir), so other apps cannot read the key, and `adb uninstall` clearing it is expected.
    private fun importedFile(context: Context, name: String) = File(File(context.filesDir, IMPORT_DIR), name)

    private fun importedPassword(context: Context): String =
        importedFile(context, PASS_NAME).takeIf { it.isFile }?.readText().orEmpty()

    private fun loadP12(file: File, password: String, label: String, kind: Kind): Identity {
        val chars = password.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        file.inputStream().use { keyStore.load(it, chars) }

        val alias = keyStore.aliases().asSequence().firstOrNull { keyStore.isKeyEntry(it) }
            ?: throw IllegalStateException("the file contains no private-key entry")
        val certificate = keyStore.getCertificate(alias) as X509Certificate

        return Identity(keyStore, certificate, chars, "$label (alias=$alias)", kind)
    }
}
