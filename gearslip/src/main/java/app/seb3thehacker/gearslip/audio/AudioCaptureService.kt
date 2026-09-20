package app.seb3thehacker.gearslip.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import app.seb3thehacker.gearslip.GearslipLog
import kotlin.math.abs

/**
 * Hears one app's audio so it can be played on the car.
 *
 * Playback capture is the one public route to another app's sound, and it hangs off a
 * MediaProjection - so, as with [app.seb3thehacker.gearslip.mirror.MirrorService], the consent
 * result is carried into a foreground service of type mediaProjection before it can be redeemed.
 * Capture is scoped to the media app's uid: notification pings and other apps stay on the phone.
 */
class AudioCaptureService : Service() {

    @Volatile private var reading = false
    private var projection: MediaProjection? = null
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) {
            GearslipLog.e("audio: started without a consent result")
            CarAudio.denied()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        projection = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            GearslipLog.e("audio: getMediaProjection failed", t)
            null
        }
        val token = projection
        if (token == null) {
            CarAudio.denied()
            stopSelf()
            return START_NOT_STICKY
        }
        token.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                GearslipLog.w("audio: projection stopped")
                stopSelf()
            }
        }, null)
        beginCapture(token)
        return START_NOT_STICKY
    }

    private fun beginCapture(token: MediaProjection) {
        val rate = CarAudio.sampleRate
        val channels = CarAudio.channels
        val uid = CarAudio.uid
        val record = try {
            val config = AudioPlaybackCaptureConfiguration.Builder(token)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .apply { if (uid >= 0) addMatchingUid(uid) }
                .build()
            val mask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(mask)
                .build()
            val minimum = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, CHUNK_BYTES * 4))
                .build()
        } catch (t: Throwable) {
            GearslipLog.e("audio: could not set up playback capture", t)
            CarAudio.denied()
            stopSelf()
            return
        }

        reading = true
        thread = Thread({
            val buffer = ByteArray(CHUNK_BYTES)
            var peak = 0
            var heardAt = System.currentTimeMillis()
            try {
                record.startRecording()
                GearslipLog.i("audio: capturing uid=$uid at ${rate}Hz x$channels")
                CarAudio.captureStarted()
                while (reading) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    for (i in 0 until n - 1 step 2) {
                        val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                        peak = maxOf(peak, abs(sample.toShort().toInt()))
                    }
                    val now = System.currentTimeMillis()
                    val level = peak / 32768f
                    if (now - heardAt >= LEVEL_LOG_MS) {
                        GearslipLog.i("audio: peak %.3f".format(level))
                        heardAt = now
                        peak = 0
                    }
                    CarAudio.deliver(buffer, n, level)
                }
            } catch (t: Throwable) {
                GearslipLog.e("audio: capture loop ended", t)
            } finally {
                runCatching { record.stop() }
                record.release()
                CarAudio.captureStopped()
            }
        }, "gearslip-audio").apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        reading = false
        runCatching { thread?.join(500) }
        runCatching { projection?.stop() }
        projection = null
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Car audio", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Playing to the car")
            .setContentText("Gearslip is sending a media app's audio to the car.")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "gearslip_audio"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_STOP = "app.seb3thehacker.gearslip.STOP_AUDIO"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        /** About 21 ms of 48 kHz stereo: small enough for lip-sync, big enough not to flood the link. */
        private const val CHUNK_BYTES = 4096
        private const val LEVEL_LOG_MS = 3_000L

        private var appContext: Context? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            appContext = context.applicationContext
            context.startForegroundService(
                Intent(context, AudioCaptureService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
        }

        fun stop() {
            val context = appContext ?: return
            context.startService(Intent(context, AudioCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
