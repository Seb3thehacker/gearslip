package app.seb3thehacker.gearslip.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.ScreenProjector
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarUi

/**
 * Runs the real car UI on the bench and shows it on the phone.
 *
 * This is the same pipeline the car gets - a VirtualDisplay hosting a Presentation, driven by
 * the same [ScreenProjector] and fed the same touches - with an ImageReader standing in for the
 * H.264 encoder. That matters for anything composited rather than drawn by Compose, such as the
 * SurfaceView a templated app draws its map into: if it appears here, it will reach the car.
 *
 * Debug affordance only. Nothing in the car session path goes through this screen.
 */
@Composable
fun CarPreviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val frame by CarEnvironment.frame.collectAsStateWithLifecycle()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var viewSize by remember { mutableStateOf(0 to 0) }
    var fullscreen by remember { mutableStateOf(false) }

    val width = frame.width
    val height = frame.height
    val density = frame.densityDpi

    val projector = remember { ScreenProjector(context) }

    DisposableEffect(width, height, density) {
        CarEnvironment.start(context)

        // COMPOSER_OVERLAY is what makes SurfaceFlinger willing to composite into this reader;
        // CPU_READ_OFTEN is what makes the resulting buffer lockable, which is how the frame
        // gets copied into a Bitmap. Without both, frames arrive but every copy fails.
        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_COMPOSER_OVERLAY or
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_CPU_READ_OFTEN,
        )
        val handler = Handler(Looper.getMainLooper())
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                bitmap = image.toBitmap(width, height)
            } catch (t: Throwable) {
                GearslipLog.e("preview: could not copy a frame", t)
            } finally {
                image.close()
            }
        }, handler)

        projector.start(reader.surface, width, height, density) { CarUi() }

        onDispose {
            projector.stop()
            reader.close()
            bitmap = null
        }
    }

    // The frame is drawn the same way in both layouts; only what surrounds it differs.
    val frameContent: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier
                .aspectRatio(width.toFloat() / height)
                .background(Color.Black)
                .onSizeChanged { viewSize = it.width to it.height }
                .pointerInteropFilter { event ->
                    val (viewWidth, viewHeight) = viewSize
                    if (viewWidth > 0 && viewHeight > 0) {
                        projector.dispatchTouch(
                            event.actionMasked.toCarAction(),
                            event.x * width / viewWidth,
                            event.y * height / viewHeight,
                        )
                    }
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "Car screen",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } ?: Text("Waiting for a frame…", color = Color.White)
        }
    }

    if (fullscreen) {
        Fullscreen(landscape = width > height, onExit = { fullscreen = false }) {
            // No fillMax here: left to the bounds alone, aspectRatio takes the whole width or the
            // whole height, whichever the car's shape allows, and the rest stays black.
            frameContent(Modifier)
        }
        return
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                "Car preview",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Text(
                "${width}x$height @ ${density}dpi - touches go to the car UI",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            frameContent(Modifier.padding(vertical = 12.dp).fillMaxWidth())

            Row {
                TextButton(onClick = onBack) { Text("Back") }
                TextButton(onClick = { fullscreen = true }) { Text("Fullscreen") }
            }
        }
    }
}

/**
 * Fills the phone screen with [content], centred and letterboxed.
 *
 * A car screen is wider than it is tall, so a portrait phone would waste most of its height on
 * one. While this is showing, the activity is turned to match and the system bars go away - both
 * are undone on the way out, so the rest of the phone UI is unaffected.
 */
@Composable
private fun Fullscreen(
    landscape: Boolean,
    onExit: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val activity = LocalContext.current.activity()

    DisposableEffect(activity, landscape) {
        if (activity == null) return@DisposableEffect onDispose { }

        val insets = WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val previousOrientation = activity.requestedOrientation
        if (landscape) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        onDispose {
            insets.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = previousOrientation
        }
    }

    BackHandler(onBack = onExit)

    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        content()

        // The frame swallows every touch inside it, so leaving needs either the back gesture or
        // a button of its own. This one sits in the corner the car UI leaves emptiest.
        FilledTonalIconButton(
            onClick = onExit,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Leave fullscreen")
        }
    }
}

/** Compose hands out a themed wrapper, so the activity is a few unwraps down. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Only the four actions [ScreenProjector.dispatchTouch] understands reach the car UI. */
private fun Int.toCarAction(): Int = when (this) {
    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
    -> this
    else -> MotionEvent.ACTION_CANCEL
}

/**
 * Copies one frame out of the reader. The reader's rows are padded to a hardware-friendly
 * stride, so the copy is made at the padded width and then cropped back.
 */
private fun android.media.Image.toBitmap(width: Int, height: Int): Bitmap {
    val plane = planes[0]
    val pixelStride = plane.pixelStride
    val rowPadding = plane.rowStride - pixelStride * width
    val padded = Bitmap.createBitmap(
        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888,
    )
    padded.copyPixelsFromBuffer(plane.buffer)
    return if (padded.width == width) padded
    else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
}
