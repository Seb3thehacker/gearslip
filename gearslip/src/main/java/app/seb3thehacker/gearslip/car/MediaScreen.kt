package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import android.provider.Settings
import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.audio.CarAudio
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.MediaApp
import app.seb3thehacker.gearslip.media.MediaArt
import app.seb3thehacker.gearslip.media.LyricsResult
import app.seb3thehacker.gearslip.media.Lyrics
import app.seb3thehacker.gearslip.media.MediaEntry
import app.seb3thehacker.gearslip.media.NowPlaying
import android.media.session.PlaybackState

/**
 * A media app on the car screen: what it is playing and its controls on the left, the tree it
 * offers to browse on the right. The app owns the content; this only draws it.
 */
@Composable
fun MediaScreen(app: MediaApp, onExit: () -> Unit) {
    val context = LocalContext.current
    val media = CarServices.media
    val phase by media.phase.collectAsStateWithLifecycle()
    val now by media.now.collectAsStateWithLifecycle()
    val browse by media.browse.collectAsStateWithLifecycle()
    val rejection by media.rejection.collectAsStateWithLifecycle()
    val capture by CarAudio.capture.collectAsStateWithLifecycle()
    val navigator = LocalCarNavigator.current
    var tab by remember {
        mutableStateOf(if (navigator.lyricsRequested) Tab.LYRICS else Tab.BROWSE).also { navigator.lyricsRequested = false }
    }

    // The connection outlives this screen: the home screen's player is the same one.
    LaunchedEffect(app) { CarServices.openMedia(app) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to apps") }
            Text(app.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            AudioBadge(capture)
        }

        when (phase) {
            CarMedia.Phase.REJECTED -> when (rejection) {
                CarMedia.Rejection.NEEDS_NOTIFICATION_ACCESS -> Notice(
                    "${app.label} keeps its library to itself.",
                    "Gearslip can still control it once it has Notification access on the phone. " +
                        "Allow it under Settings > Notifications > Notification access.",
                    action = "Open on phone" to {
                        val app = context.applicationContext
                        runCatching {
                            app.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
                CarMedia.Rejection.REFUSED -> Notice(
                    "${app.label} would not let Gearslip browse it.",
                    "Some media apps only accept Google's own host.",
                )
            }
            CarMedia.Phase.CONNECTING, CarMedia.Phase.IDLE -> Notice("Connecting to ${app.label}…", null)
            CarMedia.Phase.READY -> Row(
                Modifier.fillMaxSize().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                NowPlayingPanel(now, media, Modifier.width(250.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row {
                        TextButton(onClick = { tab = Tab.BROWSE }) {
                            Text("Browse", fontWeight = if (tab == Tab.BROWSE) FontWeight.Bold else FontWeight.Normal)
                        }
                        TextButton(onClick = { tab = Tab.LYRICS }) {
                            Text("Lyrics", fontWeight = if (tab == Tab.LYRICS) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    when (tab) {
                        Tab.BROWSE -> BrowsePanel(browse, media, app.label, Modifier.fillMaxSize())
                        Tab.LYRICS -> LyricsPanel(now, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

private enum class Tab { BROWSE, LYRICS }

/**
 * The song's lyrics, fetched when this tab opens. Synced lyrics follow the playback position with
 * the current line in the accent colour; unsynced ones are plain scrolling text.
 */
@Composable
private fun LyricsPanel(now: NowPlaying, modifier: Modifier) {
    val lyrics by produceState<LyricsState>(LyricsState.Loading, now.title, now.artist, now.album, now.durationMs) {
        value = LyricsState.Loading
        value = Lyrics.find(now.title, now.artist, now.album, now.durationMs)
            ?.let { LyricsState.Found(it) } ?: LyricsState.Missing
    }
    val list = rememberLazyListState()

    // Ticks with playback so the highlighted line moves; only synced lyrics need it.
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(now) {
        while (true) {
            position = now.currentPosition()
            kotlinx.coroutines.delay(300)
        }
    }

    when (val state = lyrics) {
        LyricsState.Loading -> Notice("Looking for lyrics…", null)
        LyricsState.Missing -> Notice(
            if (now.title.isEmpty()) "Nothing playing." else "No lyrics found for this track.",
            null,
        )
        is LyricsState.Found -> {
            val lines = state.result.lines
            val current = if (state.result.synced) lines.indexOfLast { it.timeMs <= position } else -1
            LaunchedEffect(current) {
                if (current >= 0) list.animateScrollToItem((current - 1).coerceAtLeast(0))
            }
            LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(lines) { index, line ->
                    Text(
                        line.text.ifEmpty { " " },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            !state.result.synced -> MaterialTheme.colorScheme.onSurface
                            index == current -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object Missing : LyricsState
    class Found(val result: LyricsResult) : LyricsState
}

@Composable
private fun AudioBadge(capture: CarAudio.Capture) {
    val text = when (capture) {
        CarAudio.Capture.ON -> "Audio to car"
        CarAudio.Capture.ASKING -> "Allow audio capture on the phone"
        CarAudio.Capture.DENIED -> "Audio stays on the phone"
        CarAudio.Capture.OFF -> return
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Notice(title: String, detail: String?, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        detail?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        action?.let { (label, run) ->
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.FilledTonalButton(onClick = run) { Text(label) }
        }
    }
}

// --- now playing -----------------------------------------------------------------------------

@Composable
private fun NowPlayingPanel(now: NowPlaying, media: CarMedia, modifier: Modifier) {
    val context = LocalContext.current
    val art by produceState(now.art, now.art, now.artUri) {
        value = now.art ?: MediaArt.load(context, now.artUri)
    }
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(now) {
        while (true) {
            position = now.currentPosition()
            kotlinx.coroutines.delay(500)
        }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(170.dp),
        ) {
            art?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize().clip(MaterialTheme.shapes.large), contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            now.title.ifEmpty { "Nothing playing" },
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(now.artist, now.album).filter { it.isNotEmpty() }.joinToString(" - "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        now.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }

        if (now.durationMs > 0) {
            WavyProgress(
                fraction = position.toFloat() / now.durationMs,
                playing = now.state == PlaybackState.STATE_PLAYING,
                onSeek = { media.seek((it * now.durationMs).toLong()) },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            Spacer(Modifier.height(14.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = media::previous, enabled = now.canDo(PlaybackState.ACTION_SKIP_TO_PREVIOUS), modifier = Modifier.size(56.dp)) {
                Icon(MediaIcons.Previous, "Previous", Modifier.size(32.dp))
            }
            FilledIconButton(onClick = media::togglePlay, modifier = Modifier.size(68.dp)) {
                Icon(if (now.playing) MediaIcons.Pause else Icons.Filled.PlayArrow, if (now.playing) "Pause" else "Play", Modifier.size(40.dp))
            }
            IconButton(onClick = media::next, enabled = now.canDo(PlaybackState.ACTION_SKIP_TO_NEXT), modifier = Modifier.size(56.dp)) {
                Icon(MediaIcons.Next, "Next", Modifier.size(32.dp))
            }
        }

        // Whatever extra buttons the app publishes (shuffle, repeat, like), by name: their
        // icons are resources inside the app's own package.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            now.custom.take(3).forEach { action ->
                TextButton(onClick = { media.custom(action) }) { Text(action.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// --- browsing --------------------------------------------------------------------------------

@Composable
private fun BrowsePanel(browse: app.seb3thehacker.gearslip.media.BrowseState, media: CarMedia, appLabel: String, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (media.canGoUp) {
                IconButton(onClick = media::up) { Icon(Icons.Filled.ArrowBack, "Up") }
            }
            Text(
                browse.trail.lastOrNull() ?: "Browse",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            browse.loading -> Notice("Loading…", null)
            browse.unavailable -> Notice(
                "$appLabel keeps its library to itself.",
                "Start music in the app on your phone. Play, pause, skip and seek work from here.",
            )
            browse.failed -> Notice("Could not load this list.", null)
            browse.entries.isEmpty() -> Notice("Nothing here.", null)
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(browse.entries, key = { it.id }) { entry -> EntryRow(entry, media) }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: MediaEntry, media: CarMedia) {
    val context = LocalContext.current
    val icon by produceState(entry.iconBitmap, entry.iconUri) {
        value = entry.iconBitmap ?: MediaArt.load(context, entry.iconUri)
    }
    Surface(
        onClick = { media.select(entry) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface)) {
                icon?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.subtitle.isNotEmpty()) {
                    Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (entry.browsable && entry.playable) {
                IconButton(onClick = { media.playAll(entry) }) { Icon(Icons.Filled.PlayArrow, "Play") }
            }
            if (entry.browsable) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

/** Material's transport icons, from their published path data (they live in the extended set). */
internal object MediaIcons {
    val Pause = icon("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z")
    val Next = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    /** The 3x3 app grid; not in material-icons-core. */
    val Apps = icon("Apps", "M4,8h4L8,4L4,4v4zM10,20h4v-4h-4v4zM4,20h4v-4L4,16v4zM4,14h4v-4L4,10v4zM10,14h4v-4h-4v4zM16,4v4h4L20,4h-4zM10,8h4L14,4h-4v4zM16,14h4v-4h-4v4zM16,20h4v-4h-4v4z")
    val Previous = icon("SkipPrevious", "M6,6h2v12L6,18zM9.5,12l8.5,6V6z")

    private fun icon(name: String, path: String) = ImageVector.Builder(
        name, 24.dp, 24.dp, 24f, 24f,
    ).addPath(addPathNodes(path), fill = SolidColor(androidx.compose.ui.graphics.Color.Black)).build()
}
