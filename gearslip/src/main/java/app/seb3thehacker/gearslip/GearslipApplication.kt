package app.seb3thehacker.gearslip

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.Executors

class GearslipApplication : Application() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-certificates") }
    private val androidAutoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart != ProjectionCertificates.ANDROID_AUTO_PACKAGE) return
            if (intent.action !in setOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_REMOVED)) return
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val pending = goAsync()
            worker.execute {
                try {
                    ProjectionCertificates.refreshAndroidAuto(applicationContext)
                } catch (e: Exception) {
                    GearslipLog.w("Android Auto certificate refresh failed: ${e.message}")
                } finally {
                    pending.finish()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // PACKAGE_REPLACED is not a manifest implicit-broadcast exemption on Android 8+.
        // Keep a process-lifetime receiver, and rescan at startup to cover missed updates.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
            addDataSchemeSpecificPart(ProjectionCertificates.ANDROID_AUTO_PACKAGE, android.os.PatternMatcher.PATTERN_LITERAL)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(androidAutoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(androidAutoReceiver, filter)
        }
        worker.execute {
            runCatching { ProjectionCertificates.refreshAndroidAuto(this) }
                .onFailure { GearslipLog.w("Android Auto certificate extraction failed: ${it.message}") }
        }
    }
}
