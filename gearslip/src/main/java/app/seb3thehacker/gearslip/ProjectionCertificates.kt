package app.seb3thehacker.gearslip

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Refresh Android Auto locally. Network access is restricted to the explicit setup operation. */
internal object ProjectionCertificates {
    const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    private val autoLock = Any()
    private val dhuLock = Any()
    private var lastPackageStamp: String? = null
    private var autoIdentity: CertProvider.Identity? = null
    private var dhuIdentity: CertProvider.Identity? = null
    private val changes = MutableStateFlow(0)
    val revision = changes.asStateFlow()
    @Volatile var androidAutoError: String? = null
        private set

    private fun store(context: Context) = ProjectionIdentityStore(File(context.filesDir, "projection-identities"))

    /** Also catches updates missed while the process was stopped; disabled packages remain readable. */
    fun refreshAndroidAuto(context: Context, force: Boolean = false): CertProvider.Identity? = synchronized(autoLock) {
        val storage = store(context)
        if (autoIdentity == null) autoIdentity = runCatching { storage.read(CertProvider.Source.ANDROID_AUTO) }.getOrNull()
        val info = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(ANDROID_AUTO_PACKAGE, PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (_: PackageManager.NameNotFoundException) {
            androidAutoError = if (autoIdentity == null) "Android Auto is not installed." else "Android Auto is not installed. Using the last extracted certificate."
            if (lastPackageStamp != "missing") changes.update { it + 1 }
            lastPackageStamp = "missing"
            return@synchronized autoIdentity
        } catch (e: Exception) {
            reportFailure(e)
            return@synchronized autoIdentity
        }
        val app = info.applicationInfo ?: run {
            reportFailure(IllegalStateException("Android Auto's APK information is unavailable"))
            return@synchronized autoIdentity
        }
        val apks = listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty()
        val stamp = "${info.longVersionCode}:${info.lastUpdateTime}:${apks.joinToString(":")}"
        if (!force && stamp == lastPackageStamp) return@synchronized autoIdentity
        try {
            val extracted = ProjectionIdentityExtractor.androidAuto(apks.map(::File))
            // A replacement can remove an APK while it is being read. Do not label old data as new.
            @Suppress("DEPRECATION")
            val after = context.packageManager.getPackageInfo(ANDROID_AUTO_PACKAGE, PackageManager.MATCH_DISABLED_COMPONENTS)
            check(after.lastUpdateTime == info.lastUpdateTime && after.longVersionCode == info.longVersionCode) {
                "Android Auto changed during extraction; reopen Gearslip to retry"
            }
            storage.write(CertProvider.Source.ANDROID_AUTO, extracted)
            autoIdentity = extracted
            androidAutoError = null
        } catch (e: Exception) {
            reportFailure(e)
        }
        lastPackageStamp = stamp
        changes.update { it + 1 }
        autoIdentity
    }

    fun load(context: Context, source: CertProvider.Source): CertProvider.Identity? = when (source) {
        CertProvider.Source.ANDROID_AUTO -> refreshAndroidAuto(context)
        CertProvider.Source.HEAD_UNIT -> synchronized(dhuLock) {
            dhuIdentity ?: runCatching { store(context).read(source) }.getOrNull()?.also { dhuIdentity = it }
        }
    }

    /** Successful setup is cached on disk, including across app restarts and Android Auto updates. */
    fun prepareDhu(context: Context): CertProvider.Identity = synchronized(dhuLock) {
        load(context, CertProvider.Source.HEAD_UNIT)?.let { return@synchronized it }
        val identity = store(context).getOrCreate(CertProvider.Source.HEAD_UNIT) { DhuCertificateDownloader.fetch() }
        dhuIdentity = identity
        changes.update { it + 1 }
        identity
    }

    private fun reportFailure(error: Exception) {
        val message = "Could not refresh Android Auto's certificate: ${error.message}" +
            if (autoIdentity != null) ". Using the last extracted certificate." else ""
        if (androidAutoError != message) {
            androidAutoError = message
            GearslipLog.w(message)
            changes.update { it + 1 }
        }
    }
}
