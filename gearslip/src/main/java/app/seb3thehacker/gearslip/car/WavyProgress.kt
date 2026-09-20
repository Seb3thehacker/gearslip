package app.seb3thehacker.gearslip.car

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * A progress bar whose played part is a wave, and which flattens out while paused.
 *
 * The wave runs only while [playing], so a paused player costs the encoder nothing. Tapping or
 * dragging seeks; the position is committed on release rather than on every pixel of a drag,
 * because each commit is a request to the media app.
 */
@Composable
fun WavyProgress(
    fraction: Float,
    playing: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val played = MaterialTheme.colorScheme.primary
    val remaining = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, tween(400), label = "wave")
    val phase = if (playing) {
        rememberInfiniteTransition(label = "phase").animateFloat(
            0f, (2 * PI).toFloat(),
            infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        ).value
    } else 0f

    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let(onSeek); dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        val stroke = 4.dp.toPx()
        val mid = size.height / 2
        val end = size.width * shown
        val wavelength = 22.dp.toPx()
        val amp = 3.5.dp.toPx() * amplitude

        // Unplayed part: a straight line, so the wave reads as "this much is done".
        drawLine(remaining, Offset(end, mid), Offset(size.width, mid), stroke, StrokeCap.Round)

        val wave = Path().apply {
            moveTo(0f, mid + amp * sin(phase.toDouble()).toFloat())
            var x = 0f
            while (x < end) {
                x = minOf(end, x + 2f)
                lineTo(x, mid + amp * sin(2 * PI * x / wavelength + phase).toFloat())
            }
        }
        drawPath(wave, played, style = Stroke(stroke, cap = StrokeCap.Round))
        drawLine(played, Offset(end, mid - 10.dp.toPx()), Offset(end, mid + 10.dp.toPx()), stroke, StrokeCap.Round)
    }
}
