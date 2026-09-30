package app.seb3thehacker.gearslip.car.theme

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A seek bar in the current theme's finish: the track is set into the surface (drawn latched, so
 * themes with a pressed-in look sink it), the played part and the knob are raised like a button.
 * Tapping or dragging seeks; the position is committed on release rather than on every pixel of a
 * drag, because each commit is a request to the media app.
 */
@Composable
fun GsSeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    val track = 8.dp
    val knob = 20.dp

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { dragging?.let(onSeek); dragging = null },
                            onDragCancel = { dragging = null },
                        ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
                    },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The knob's centre travels from half a knob in to half a knob from the end, so it never
        // hangs off either edge; the fill runs to its centre.
        val travel = maxWidth - knob
        val centre = knob / 2 + travel * shown
        Box(
            Modifier
                .fillMaxWidth()
                .height(track)
                .gsSurface(scheme.onSurface.copy(alpha = 0.14f), CircleShape, latched = true),
        )
        Box(
            Modifier
                .width(centre)
                .height(track)
                .gsSurface(scheme.primary, CircleShape),
        )
        Box(
            Modifier
                .offset(x = centre - knob / 2)
                .size(knob)
                .gsSurface(scheme.primary, CircleShape, pressed = dragging != null),
        )
    }
}
