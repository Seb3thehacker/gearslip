package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import android.media.session.PlaybackState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.MediaArt
import kotlinx.coroutines.delay

/**
 * The home screen: the map on the left, what is playing on the right.
 *
 * The map is whatever navigation app was used last, brought back on its own. The player only
 * takes its share of the screen while something is loaded; otherwise the map has all of it.
 */
@Composable
fun CarHome() {
    val frame by CarEnvironment.frame.collectAsStateWithLifecycle()
    val navStatus by CarServices.nav.status.collectAsStateWithLifecycle()
    val phase by CarServices.media.phase.collectAsStateWithLifecycle()
    val now by CarServices.media.now.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { CarServices.autostart(frame) }
    LaunchedEffect(phase) { if (phase == CarMedia.Phase.READY) CarServices.autoplayIfDue() }

    val showPlayer = phase == CarMedia.Phase.READY && now.hasTrack

    Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .weight(if (showPlayer) 1.8f else 1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(EMBEDDED_RADIUS)),
        ) {
            MapPane(navStatus, frame)
        }
        if (showPlayer) {
            MiniPlayer(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun MapPane(status: CarAppConnection.Status, frame: CarEnvironment.Frame) {
    val navigator = LocalCarNavigator.current
    if (status.phase == CarAppConnection.Phase.RUNNING) {
        CarAppStage(CarServices.nav, status, frame, embedded = true) { CarServices.stopNav() }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val hasLast = CarSettings.lastNav.value != null
        when (status.phase) {
            CarAppConnection.Phase.BINDING, CarAppConnection.Phase.HANDSHAKE ->
                Text("Starting ${status.app ?: "the map"}…", style = MaterialTheme.typography.titleMedium)
            CarAppConnection.Phase.REJECTED, CarAppConnection.Phase.FAILED -> {
                Text("${status.app ?: "The map app"} would not start.", style = MaterialTheme.typography.titleMedium)
                status.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = navigator::apps) { Text("Choose another app") }
            }
            else -> {
                Text(
                    if (hasLast) "Starting the map…" else "No map app chosen yet.",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!hasLast) {
                    Text(
                        "Open Car apps and connect a navigation app; it will start here next time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { navigator.apps() }) { Text("Car apps") }
                }
            }
        }
    }
}

/** Play/pause and a seek bar, and nothing else: the full controls are in the media screen. */
@Composable
private fun MiniPlayer(modifier: Modifier) {
    val media = CarServices.media
    val now by media.now.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }

    var position by remember { mutableStateOf(0L) }
    LaunchedEffect(now) {
        while (true) {
            position = now.currentPosition()
            delay(500)
        }
    }

    val navigator = LocalCarNavigator.current
    Surface(
        onClick = navigator::media,
        modifier = modifier,
        shape = RoundedCornerShape(EMBEDDED_RADIUS),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(120.dp).clip(RoundedCornerShape(EMBEDDED_RADIUS))) {
                art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                now.title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                now.artist,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )

            if (now.durationMs > 0) {
                WavyProgress(
                    fraction = position.toFloat() / now.durationMs,
                    playing = now.state == PlaybackState.STATE_PLAYING,
                    onSeek = { media.seek((it * now.durationMs).toLong()) },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                Spacer(Modifier.height(16.dp))
            }

            FilledIconButton(onClick = media::togglePlay, modifier = Modifier.size(64.dp)) {
                Icon(
                    if (now.playing) MediaIcons.Pause else Icons.Filled.PlayArrow,
                    if (now.playing) "Pause" else "Play",
                    Modifier.size(38.dp),
                )
            }
        }
    }
}
