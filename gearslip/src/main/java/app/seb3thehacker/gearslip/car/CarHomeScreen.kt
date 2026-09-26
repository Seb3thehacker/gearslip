package app.seb3thehacker.gearslip.car

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.NowPlaying
import kotlinx.coroutines.launch

/**
 * The home screen: the map on the left, lyrics on the right when asked for.
 *
 * The map is whatever navigation app was used last, brought back on its own. Playback itself is
 * controlled from the nav bar's pill everywhere, including here - this column only opens when the
 * pill's lyrics button is on, and closes itself back to a full-width map once nothing is playing.
 */
@Composable
fun CarHome() {
    val frame by CarEnvironment.frame.collectAsStateWithLifecycle()
    val navStatus by CarServices.nav.status.collectAsStateWithLifecycle()
    val phase by CarServices.media.phase.collectAsStateWithLifecycle()
    val now by CarServices.media.now.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { CarServices.autostart(frame) }
    LaunchedEffect(phase) { if (phase == CarMedia.Phase.READY) CarServices.autoplayIfDue() }

    val navigator = LocalCarNavigator.current
    val showLyrics = phase == CarMedia.Phase.READY && now.isActive && navigator.lyricsOpen

    Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .weight(if (showLyrics) 2.1f else 1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(EMBEDDED_RADIUS)),
        ) {
            MapPane(navStatus, frame)
        }
        if (showLyrics) {
            LyricsColumn(now, Modifier.weight(1f).fillMaxHeight())
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
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        when (status.phase) {
            CarAppConnection.Phase.BINDING, CarAppConnection.Phase.HANDSHAKE ->
                Text("Starting ${status.app ?: "the map"}…", style = MaterialTheme.typography.titleMedium)
            CarAppConnection.Phase.REJECTED, CarAppConnection.Phase.FAILED -> {
                Text("${status.app ?: "The map app"} would not start.", style = MaterialTheme.typography.titleMedium)
                status.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = navigator::apps) { Text("Choose another app") }
            }
            else -> {
                // IDLE with a last app on record means the driver stopped it themselves - it will
                // not come back on its own, so the screen must offer a way to bring it back rather
                // than sit on a "starting" message that never resolves.
                Text(
                    if (hasLast) "Map stopped." else "No map app chosen yet.",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (hasLast) {
                    TextButton(onClick = { scope.launch { CarServices.reconnectNav(frame) } }) { Text("Reopen") }
                } else {
                    Text(
                        "Open Car apps and connect a navigation app; it will start here next time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { navigator.apps() }) { Text("Car apps") }
            }
        }
    }
}

/**
 * Lyrics for whatever the nav bar's pill is playing, beside the map. Transport controls live in
 * the pill itself now, not here - this column has exactly one job.
 */
@Composable
private fun LyricsColumn(now: NowPlaying, modifier: Modifier) {
    val navigator = LocalCarNavigator.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(EMBEDDED_RADIUS),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    now.title.ifEmpty { "Lyrics" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = navigator::toggleLyrics, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Hide lyrics", Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            LyricsPanel(now, Modifier.weight(1f).fillMaxWidth())
        }
    }
}
