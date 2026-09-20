package app.seb3thehacker.gearslip

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.CarUi
import java.io.File
import java.io.FileOutputStream

/**
 * Debug-only harness: runs the real car UI on a virtual display (the same path a head unit
 * gets), taps where told, and saves what it rendered as PNGs in the external files dir.
 *
 *   adb shell am start -n app.seb3thehacker.gearslip/.CarPreviewActivity \
 *       --ei w 800 --ei h 480 --ei dpi 160 --es taps "400,440;90,440"
 *
 * Writes preview-0.png (before any tap), then one more per tap.
 */
class CarPreviewActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var projector: ScreenProjector? = null
    private var reader: ImageReader? = null
    @Volatile private var latest: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GearslipLog.init(this)
        CarSettings.init(this)
        CarEnvironment.start(this)

        val w = intent.getIntExtra("w", 800)
        val h = intent.getIntExtra("h", 480)
        val dpi = intent.getIntExtra("dpi", 160)
        val taps = intent.getStringExtra("taps").orEmpty().split(";").filter { it.isNotBlank() }
            .map { it.split(",").let { p -> p[0].toFloat() to p[1].toFloat() } }

        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val padded = plane.rowStride / plane.pixelStride
                val bmp = Bitmap.createBitmap(padded, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                latest = Bitmap.createBitmap(bmp, 0, 0, w, h)
            }
        }, main)

        val p = ScreenProjector(this).also { projector = it }
        p.start(imageReader.surface, w, h, dpi) { CarUi() }

        GearslipLog.i("preview: displayId=${p.displayId}")
        if (intent.getBooleanExtra("hide", false)) p.setOverlayVisible(false)

        intent.getStringExtra("launch")?.let { pkg ->
            main.postDelayed({
                packageManager.getLaunchIntentForPackage(pkg)?.let { p.launchApp(it) }
                    ?: GearslipLog.e("preview: no launch intent for $pkg")
            }, 1500)
        }

        var step = 0
        fun snap() {
            val bmp = latest ?: return GearslipLog.e("preview: no frame yet for step $step")
            val out = File(getExternalFilesDir(null), "preview-$step.png")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            GearslipLog.i("preview: wrote ${out.name}")
            step++
        }
        // Free-running mode: keep snapshotting on a timer so the display can be driven from
        // outside (adb `am start --display` / `input -d`) while the harness records it.
        val snaps = intent.getIntExtra("snaps", 0)
        if (snaps > 0) {
            val every = intent.getIntExtra("every", 3000).toLong()
            repeat(snaps) { i -> main.postDelayed({ snap() }, 2000L + every * i) }
            return
        }

        main.postDelayed({
            snap()
            taps.forEachIndexed { i, (x, y) ->
                main.postDelayed({
                    p.dispatchTouch(MotionEvent.ACTION_DOWN, x, y)
                    main.postDelayed({ p.dispatchTouch(MotionEvent.ACTION_UP, x, y) }, 80)
                }, 2500L * (i + 1))
                main.postDelayed({ snap() }, 2500L * (i + 1) + 1500)
            }
        }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        projector?.stop()
        reader?.close()
        CarEnvironment.stop()
    }
}
