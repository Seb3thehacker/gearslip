package app.seb3thehacker.gearslip.host

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Lets a hosted navigation app keep receiving the phone's location while Gearslip is not on screen.
 *
 * Android only gives an app location in the background if something visible to the user is
 * running for it. A nav app bound as a car app has nothing of its own showing, so it goes
 * without a fix as soon as the phone screen leaves it. Binding with BIND_INCLUDE_CAPABILITIES
 * lends it this service's location capability, which is why this foreground service exists.
 * It needs the ordinary location permission, granted on the phone.
 */
class LocationKeepAlive : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Navigation location", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Gearslip is sharing location with the car map")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "gearslip_location"
        private const val NOTIFICATION_ID = 44

        /** Public flag value; the constant itself is hidden from the SDK. */
        const val BIND_INCLUDE_CAPABILITIES = 0x1000

        fun start(context: Context) {
            val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) runCatching { context.startForegroundService(Intent(context, LocationKeepAlive::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationKeepAlive::class.java))
        }
    }
}
