package app.seb3thehacker.gearslip

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.cert.X509Certificate

/**
 * Supplies the certificate the phone presents to the head unit.
 *
 * User-supplied certificates retain priority over the bundled default. Sources, in order:
 *
 *  1. **Imported** in the app (Settings > Certificate): a PKCS#12 file plus its password,
 *     copied into the app's private storage.
 *  2. **Downloaded** from the public opencardev/aasdk repository. Fetched only when the
 *     user asks; the aasdk identity is not bundled.
 *  3. **Staged over adb** into the app's external files directory, the way the spike has
 *     always done it. Still honoured, so existing setups keep working untouched:
 *
 *       openssl pkcs12 -export -in headunit.crt -inkey headunit.key \
 *           -name phone -passout pass:aaspike -out phone.p12
 *       adb push phone.p12 /sdcard/Android/data/app.seb3thehacker.gearslip/files/phone.p12
 *
 *     Default password is "aaspike" (kept as-is so an already-staged phone.p12 doesn't need
 *     regenerating); a `phone.pass` file next to it overrides it.
 *  4. The selected bundled projection identity (Android Auto by default, or DHU).
 */
object CertProvider {
    private val log = GearslipLog.tagged("CERT")

    private const val P12_NAME = "phone.p12"
    private const val PASS_NAME = "phone.pass"
    private const val DEFAULT_PASSWORD = "aaspike"
    private const val IMPORT_DIR = "identity"

    enum class Kind { IMPORTED, DOWNLOADED, ADB_STAGED, SELF_SIGNED, BUNDLED }

    enum class Source(val label: String, val description: String) {
        ANDROID_AUTO("Android Auto", "bundled Android Auto 17.9.664004 projection identity"),
        HEAD_UNIT("Head unit (DHU)", "bundled DHU 2.0 fallback identity"),
    }

    class Identity(
        val keyStore: KeyStore,
        val certificate: X509Certificate,
        val password: CharArray,
        val source: String,
        val kind: Kind = Kind.SELF_SIGNED,
    )

    /**
     * The identity for the next connection. A newly imported or pushed cert takes effect on the
     * next connect: see [loadFromFiles] for how the cache notices.
     */
    fun load(context: Context, source: Source = AppSettings.certificateSource(context)): Identity =
        loadFromFiles(context) ?: loadBundled(context, source)

    /** The active identity, including the bundled default, for the certificate summary. */
    fun loadSupplied(context: Context): Identity? = load(context)

    /**
     * Unlocking a PKCS#12 file takes about a second on a Pixel 6, which a head unit waiting for
     * its first reply may not wait out. So the unlocked identity is kept, keyed on the files it
     * could come from: importing, downloading, removing or pushing a cert changes the key.
     */
    @Volatile private var cached: Pair<String, Identity>? = null

    private fun sourcesKey(context: Context): String {
        val dir = context.getExternalFilesDir(null)
        val files = listOf(
            importedFile(context, P12_NAME),
            downloadedFile(context, P12_NAME),
            dir?.let { File(it, P12_NAME) },
            dir?.let { File(it, PASS_NAME) },
        )
        return files.joinToString("|") { f -> f?.let { "${it.path}:${it.lastModified()}:${it.length()}" } ?: "-" }
    }

    /** Unlocks the certificate ahead of time, so the first connection doesn't wait on it. */
    fun warm(context: Context) {
        runCatching { load(context) }
    }

    private fun loadFromFiles(context: Context): Identity? {
        val key = sourcesKey(context)
        cached?.let { (k, identity) -> if (k == key) return identity }
        val identity = unlockFromFiles(context) ?: return null
        cached = key to identity
        return identity
    }

    private fun unlockFromFiles(context: Context): Identity? {
        val imported = importedFile(context, P12_NAME)
        if (imported.isFile) {
            runCatching {
                return loadP12(imported, importedPassword(context), "imported certificate", Kind.IMPORTED)
            }.onFailure { log.e("could not load the imported certificate - trying the next source", it) }
        }

        val downloaded = downloadedFile(context, P12_NAME)
        if (downloaded.isFile) {
            runCatching {
                return loadP12(downloaded, DEFAULT_PASSWORD, "downloaded certificate", Kind.DOWNLOADED)
            }.onFailure { log.e("could not load the downloaded certificate - trying the next source", it) }
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

    /** Whether a downloaded certificate is stored (may or may not also be imported). */
    fun hasDownloaded(context: Context): Boolean = downloadedFile(context, P12_NAME).isFile

    /** Drops the imported identity; the next source in line (downloaded, adb-staged, then self-signed) takes over. */
    fun removeImported(context: Context) {
        importedFile(context, P12_NAME).delete()
        importedFile(context, PASS_NAME).delete()
        log.i("removed the imported certificate")
    }

    /** Drops the downloaded identity. */
    fun removeDownloaded(context: Context) {
        downloadedFile(context, P12_NAME).delete()
        downloadedFile(context, PASS_NAME).delete()
        log.i("removed the downloaded certificate")
    }

    /** Whether any certificate is available (imported, downloaded, or adb-staged). */
    fun hasAnyCert(context: Context): Boolean =
        hasImported(context) || hasDownloaded(context) || hasAdbStaged(context)

    private fun hasAdbStaged(context: Context): Boolean =
        context.getExternalFilesDir(null)?.let { File(it, P12_NAME).isFile } == true

    /**
     * Validates raw PKCS#12 bytes with [password] and, if good, writes them as the imported
     * identity. Used when the app assembles a certificate in memory (e.g. from a download)
     * rather than importing from a file picker.
     */
    fun importFromBytes(context: Context, p12Bytes: ByteArray, password: String): Identity {
        val staging = File(context.cacheDir, "import-$P12_NAME")
        try {
            staging.writeBytes(p12Bytes)
            val identity = loadP12(staging, password, "imported certificate", Kind.IMPORTED)

            val target = importedFile(context, P12_NAME)
            target.parentFile?.mkdirs()
            staging.copyTo(target, overwrite = true)
            importedFile(context, PASS_NAME).writeText(password)
            log.i("imported a certificate from bytes: ${identity.certificate.subjectX500Principal}")
            return identity
        } finally {
            staging.delete()
        }
    }

    /**
     * Downloads the publicly available head unit certificate from the opencardev/aasdk
     * repository, converts it from PEM to PKCS#12, and stores it ready for use.
     * Runs synchronously (caller is responsible for threading). Throws on any failure.
     */
    fun downloadAndImport(context: Context): Identity {
        val download = CertDownloader.fetch()
        val result = PemKeyParser.build(download.certificatePem, download.privateKeyPem)

        val target = downloadedFile(context, P12_NAME)
        target.parentFile?.mkdirs()
        val p12Password = DEFAULT_PASSWORD
        val keyStore = result.keyStore
        val p12Bytes = ByteArrayOutputStream().use { stream ->
            keyStore.store(stream, p12Password.toCharArray())
            stream.toByteArray()
        }
        target.writeBytes(p12Bytes)
        downloadedFile(context, PASS_NAME).writeText(p12Password)

        log.i("downloaded and stored certificate: ${result.certificate.subjectX500Principal}")
        return Identity(keyStore, result.certificate, p12Password.toCharArray(),
            "downloaded certificate (opencardev/aasdk)", Kind.DOWNLOADED)
    }

    // The imported files live in the app's private internal storage (not the external files
    // dir), so other apps cannot read the key, and `adb uninstall` clearing it is expected.
    private fun importedFile(context: Context, name: String) = File(File(context.filesDir, IMPORT_DIR), name)

    private fun downloadedFile(context: Context, name: String) =
        File(File(context.filesDir, "$IMPORT_DIR-downloaded"), name)

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

    private val bundledCache = mutableMapOf<Int, Identity>()

    /** Expiry is informational; the head unit decides whether to accept the certificate. */
    internal fun loadBundled(context: Context, source: Source): Identity = when (source) {
        Source.ANDROID_AUTO -> loadBundled(context, R.raw.projection_chain, R.raw.projection_key, source)
        Source.HEAD_UNIT -> loadBundled(context, R.raw.projection_fallback, R.raw.projection_fallback_key, source)
    }

    /** Both identities are immutable resources; parse each at most once per process. */
    @Synchronized
    private fun loadBundled(context: Context, certificate: Int, key: Int, source: Source): Identity =
        bundledCache.getOrPut(certificate) {
            readIdentity(
                context.resources.openRawResource(certificate).use { it.readBytes() },
                context.resources.openRawResource(key).use { it.readBytes() },
                source.description,
            )
        }

    internal fun readIdentity(
        chainBytes: ByteArray,
        keyBytes: ByteArray,
        source: String = Source.ANDROID_AUTO.description,
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
        return Identity(keyStore, chain.first(), password, source, Kind.BUNDLED)
    }
}
