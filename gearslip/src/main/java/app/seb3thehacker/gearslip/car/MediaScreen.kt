package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import android.provider.Settings
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import app.seb3thehacker.gearslip.media.QueueTrack
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
    val queue by media.queue.collectAsStateWithLifecycle()
    val browse by media.browse.collectAsStateWithLifecycle()
    val rejection by media.rejection.collectAsStateWithLifecycle()
    val capture by CarAudio.capture.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.NOW_PLAYING) }

    // The connection outlives this screen: the home screen's player is the same one.
    LaunchedEffect(app) { CarServices.openMedia(app) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // The art rail sits at the top, level with the tab row beside it, on every tab -
                // not under a full-width tab bar the way it used to be. Transport and the
                // progress bar stay on it always; only the app's extra buttons (shuffle, repeat,
                // like) are exclusive to the "Now playing" tab's own content area, since those are
                // the one thing that isn't already always visible here.
                NowPlayingRail(now, media, Modifier.width(220.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MediaTab("Now playing", tab == Tab.NOW_PLAYING, Modifier.weight(1f)) { tab = Tab.NOW_PLAYING }
                        MediaTab("Browse", tab == Tab.BROWSE, Modifier.weight(1f)) { tab = Tab.BROWSE }
                        MediaTab("Up next", tab == Tab.QUEUE, Modifier.weight(1f)) { tab = Tab.QUEUE }
                        MediaTab("Lyrics", tab == Tab.LYRICS, Modifier.weight(1f)) { tab = Tab.LYRICS }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            Tab.NOW_PLAYING -> NowPlayingExtras(now, media, Modifier.fillMaxSize())
                            Tab.BROWSE -> BrowsePanel(browse, media, app.label, Modifier.fillMaxSize())
                            Tab.QUEUE -> QueuePanel(queue, now, media, Modifier.fillMaxSize())
                            Tab.LYRICS -> LyricsPanel(now, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

private enum class Tab { NOW_PLAYING, BROWSE, QUEUE, LYRICS }

/** A bigger, easier-to-hit tab than a plain [TextButton] - this row is the only navigation on the screen. */
@Composable
private fun MediaTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

/**
 * The song's lyrics, fetched when this tab opens. Synced lyrics follow the playback position with
 * the current line in the accent colour; unsynced ones are plain scrolling text.
 */
@Composable
internal fun LyricsPanel(now: NowPlaying, modifier: Modifier) {
    val context = LocalContext.current
    val lyrics by produceState<LyricsState>(LyricsState.Loading, now.title, now.artist, now.album, now.durationMs) {
        value = LyricsState.Loading
        value = Lyrics.find(context, now.title, now.artist, now.album, now.durationMs)
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

/** "1:05" for under a minute past the hour, "1:01:05" once an hour is involved. */
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

// --- now playing -----------------------------------------------------------------------------

/**
 * The art rail beside every tab: art, title, transport and the seek bar - always visible no
 * matter which tab is open. Only the app's extra buttons (shuffle, repeat, like) are held back
 * for [NowPlayingExtras], since those are the one thing that isn't already always shown here.
 */
@Composable
internal fun NowPlayingRail(now: NowPlaying, media: CarMedia, modifier: Modifier) {
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

    // Whether the progress bar (and its duration text) is showing depends on whether the app has
    // reported a duration yet, which can arrive a beat after everything else - so this rail's
    // content height isn't fixed. A plain fillMaxHeight Column just clips whatever doesn't fit
    // once that row appears; scrolling means it's always reachable instead.
    BoxWithConstraints(modifier) {
        // Off the space actually available rather than a fixed dp value, so it doesn't overrun a
        // short car frame the way a flat size did on anything shorter than a phone screen.
        val artSize = (minOf(maxWidth, maxHeight * 0.45f) * 1.2f).coerceAtLeast(96.dp)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(artSize),
            ) {
                art?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize().clip(MaterialTheme.shapes.large), contentScale = ContentScale.Crop)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                now.title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(now.artist, now.album).filter { it.isNotEmpty() }.joinToString(" - "),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            now.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, maxLines = 2) }
            Spacer(Modifier.height(16.dp))

            if (now.durationMs > 0) {
                WavyProgress(
                    fraction = position.toFloat() / now.durationMs,
                    playing = now.state == PlaybackState.STATE_PLAYING,
                    onSeek = { media.seek((it * now.durationMs).toLong()) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatDuration(now.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
        }
    }
}

/**
 * The "Now playing" tab's own content: just the app's extra buttons (shuffle, repeat, like), by
 * name - their icons are resources inside the app's own package, so there's nothing to draw but
 * the label. Transport and progress already live on [NowPlayingRail], visible on every tab.
 */
@Composable
private fun NowPlayingExtras(now: NowPlaying, media: CarMedia, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        // At the library root there's no trail and nowhere to go up to, so there's nothing this
        // row could say that the app's name in the top-left corner hasn't already - showing it
        // again here (as "Browse", or as the app's own name) was just the same label twice.
        if (media.canGoUp || browse.trail.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (media.canGoUp) {
                    IconButton(onClick = media::up) { Icon(Icons.Filled.ArrowBack, "Up") }
                }
                browse.trail.lastOrNull()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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

// --- up next -----------------------------------------------------------------------------------

/**
 * Whatever the app has queued up, starting right after the track that's playing now - not the
 * whole queue from the top, which would just repeat what [NowPlayingRail] already shows. Apps
 * that never publish a queue (many don't) show an empty notice rather than a permanently blank tab.
 */
@Composable
private fun QueuePanel(queue: List<QueueTrack>, now: NowPlaying, media: CarMedia, modifier: Modifier) {
    val upNext = remember(queue, now.activeQueueItemId) {
        val current = queue.indexOfFirst { it.queueId == now.activeQueueItemId }
        if (current >= 0) queue.drop(current + 1) else queue
    }
    Column(modifier) {
        if (upNext.isEmpty()) {
            Notice(
                "Nothing queued.",
                "${if (now.title.isEmpty()) "Nothing playing" else "Only what's already playing"} - " +
                    "not every app tells Gearslip what comes next.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(upNext, key = { it.queueId }) { track -> QueueRow(track, media) }
            }
        }
    }
}

@Composable
private fun QueueRow(track: QueueTrack, media: CarMedia) {
    val context = LocalContext.current
    val icon by produceState(track.iconBitmap, track.iconUri) {
        value = track.iconBitmap ?: MediaArt.load(context, track.iconUri)
    }
    Surface(
        onClick = { media.playQueueItem(track) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(62.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface)) {
                icon?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.subtitle.isNotEmpty()) {
                    Text(track.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Filled.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Box(Modifier.size(62.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface)) {
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
    val Equalizer = icon("Equalizer", "M10,20h4L14,4h-4v16zM4,20h4v-8L4,12v8zM16,9v11h4L20,9h-4z")

    private fun icon(name: String, path: String) = ImageVector.Builder(
        name, 24.dp, 24.dp, 24f, 24f,
    ).addPath(addPathNodes(path), fill = SolidColor(androidx.compose.ui.graphics.Color.Black)).build()
}
