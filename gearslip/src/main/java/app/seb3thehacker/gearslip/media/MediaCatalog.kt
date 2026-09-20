package app.seb3thehacker.gearslip.media

import app.seb3thehacker.gearslip.host.AndroidAuto
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.media.MediaBrowserService

/** A media app that has told the system it belongs in a car. */
class MediaApp(
    val component: ComponentName,
    val label: String,
    /** Whose audio to capture: playback capture is scoped to one uid so nothing else leaks in. */
    val uid: Int,
)

/**
 * Finds media apps that opted in to Android Auto.
 *
 * An app is a car media app when it exports a MediaBrowserService *and* declares the
 * `com.google.android.gms.car.application` meta-data pointing at a descriptor with
 * `<uses name="media"/>`. The second half is what separates Deezer from a phone-only player
 * that merely exposes a browser service to Bluetooth and Wear.
 */
object MediaCatalog {

    private const val CAR_META = "com.google.android.gms.car.application"

    fun installed(context: Context): List<MediaApp> {
        val manager = context.packageManager
        return manager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.serviceInfo.packageName != context.packageName }
            .filterNot { AndroidAuto.matches(it.serviceInfo.packageName, it.loadLabel(manager).toString()) }
            .filter { declaresCar(manager, it.serviceInfo) }
            .map {
                val info = it.serviceInfo
                MediaApp(
                    component = ComponentName(info.packageName, info.name),
                    label = info.applicationInfo?.loadLabel(manager)?.toString() ?: info.packageName,
                    uid = info.applicationInfo?.uid ?: -1,
                )
            }
            .distinctBy { it.component.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun declaresCar(manager: PackageManager, service: android.content.pm.ServiceInfo): Boolean {
        if (service.metaData?.containsKey(CAR_META) == true) return true
        return runCatching {
            manager.getApplicationInfo(service.packageName, PackageManager.GET_META_DATA)
                .metaData?.containsKey(CAR_META) == true
        }.getOrDefault(false)
    }
}
