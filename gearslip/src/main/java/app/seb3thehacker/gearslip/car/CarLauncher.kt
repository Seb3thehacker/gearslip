package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.car.theme.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import app.seb3thehacker.gearslip.host.KnownApps
import app.seb3thehacker.gearslip.host.CarAppCatalog
import app.seb3thehacker.gearslip.host.TemplateApp
import app.seb3thehacker.gearslip.media.MediaApp
import app.seb3thehacker.gearslip.media.MediaCatalog
import app.seb3thehacker.gearslip.notify.MessagingApp
import app.seb3thehacker.gearslip.notify.MessagingCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One tile on the launcher: either a phone app's own icon or a built-in glyph. */
private class Tile(
    val label: String,
    val icon: ImageBitmap? = null,
    val glyph: ImageVector? = null,
    /** Tested from start to finish with Gearslip; drawn with a check mark. */
    val verified: Boolean = false,
    /** Known not to work; drawn with a red X instead of being left off the launcher. */
    val broken: Boolean = false,
    /** Starts and can be used, but rough enough not to call working; drawn with a yellow mark. */
    val partial: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** A launcher app's data, kept apart from [Tile] so it can be cached without capturing click state. */
internal class Entry(
    val label: String,
    val icon: ImageBitmap?,
    val template: TemplateApp? = null,
    val media: MediaApp? = null,
    val builtIn: BuiltInApp? = null,
    val messaging: MessagingApp? = null,
) {
    /** What a pin or a nav-bar shortcut remembers this entry as; null for nothing pinnable. */
    val componentId: String?
        get() = builtIn?.let { BUILT_IN_PREFIX + it.id }
            ?: messaging?.let { MESSAGING_PREFIX + it.packageName }
            ?: (template?.component ?: media?.component)?.flattenToString()

    val packageName: String?
        get() = template?.component?.packageName ?: media?.component?.packageName ?: messaging?.packageName
}

/** A messaging app has no component of its own to open, so its pin carries the package instead. */
internal const val MESSAGING_PREFIX = "messages:"

/**
 * The installed car and media apps with their icons. Scanning the package manager and decoding
 * icons is slow, so [Prefetch] does it ahead of time and again at the start of each car session,
 * which is when new installs are picked up.
 */
internal object LauncherCache {
    /** Observed, not read once: the nav bar is drawn before the car session's rescan finishes,
     * and an app installed since the last scan has to show up there when it does. */
    private val flow = MutableStateFlow<List<Entry>?>(null)
    val state: StateFlow<List<Entry>?> = flow.asStateFlow()

    val entries: List<Entry>? get() = flow.value

    /** Returns the scan if there is one; [force] rescans. Synchronized so two callers share one scan. */
    @Synchronized
    fun load(context: Context, force: Boolean = false): List<Entry> {
        if (!force) entries?.let { return it }
        fun iconOf(pkg: String) = runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap()
        }.getOrNull()

        val navApps = CarAppCatalog.installed(context)
        val mediaApps = MediaCatalog.installed(context)
        // Some apps (Spotify among them) ship both a Car App Library template and a legacy
        // MediaBrowserService, which otherwise land as two identically-labeled tiles with no
        // way to tell them apart. They're genuinely different screens - browsing versus Now
        // Playing - so only the label needs disambiguating, not the tile itself.
        val mediaPackages = mediaApps.map { it.component.packageName }.toSet()

        val nav = navApps.map {
            val label = if (it.component.packageName in mediaPackages) "${it.label} · Browse" else it.label
            Entry(label, iconOf(it.component.packageName), template = it)
        }
        val media = mediaApps.map { Entry(it.label, iconOf(it.component.packageName), media = it) }
        // A messenger that is also a car media app keeps only its player tile.
        val messaging = MessagingCatalog.installed(context)
            .filter { it.packageName !in mediaPackages }
            .map { Entry(it.label, iconOf(it.packageName), messaging = it) }
        return (nav + media + messaging).sortedBy { it.label.lowercase() }.also { flow.value = it }
    }
}

/** Every app the launcher knows: Gearslip's own and the installed ones, rescans included. */
@Composable
internal fun rememberAllEntries(): List<Entry> {
    val context = LocalContext.current
    val installed by LauncherCache.state.collectAsState()
    LaunchedEffect(context) {
        if (LauncherCache.entries == null) withContext(Dispatchers.IO) { LauncherCache.load(context) }
    }
    return remember(installed) { BuiltInApps.entries + installed.orEmpty() }
}

/** An app by the id a pin or the running list knows it by, built-in or installed. */
internal fun findEntry(id: String): Entry? =
    BuiltInApps.entries.find { it.componentId == id } ?: LauncherCache.entries?.find { it.componentId == id }

/** Rescans the car and media apps now, replacing the cached list; the launcher keeps showing the old one meanwhile. */
fun warmLauncherApps(context: Context) { LauncherCache.load(context, force = true) }

/**
 * Connects or opens [entry] - what tapping its tile does, shared with the nav bar's pinned
 * shortcuts and its open-app icon so there is exactly one place that knows how to launch either
 * kind of app.
 */
internal fun launchEntry(entry: Entry, navigator: CarNavigator, frame: CarEnvironment.Frame) {
    entry.builtIn?.let { it.open(navigator); return }
    entry.template?.let {
        if (it.isNavigation) {
            CarServices.connectNav(it, frame)
            navigator.home()
        } else {
            CarServices.connectBrowse(it, frame)
            navigator.browse(entry.label)
        }
    }
    entry.media?.let { CarServices.openMedia(it); navigator.media() }
    entry.messaging?.let { navigator.messages(it.packageName, it.label) }
}

/** The app launcher: Web and Screen sharing, every car app the phone has, then Settings, as an icon grid. */
@Composable
fun CarLauncher() {
    val navigator = LocalCarNavigator.current
    val frame by CarEnvironment.frame.collectAsState()

    // Painted from the cache at once; scanned only if [Prefetch] has not got there first.
    val all = rememberAllEntries()
    val running = rememberRunningApps()
    val showVehicleData = rememberVehicleDataShown()
    var showBadgeKey by remember { mutableStateOf(false) }

    fun tileOf(entry: Entry): Tile {
        val pkg = entry.packageName
        val id = entry.componentId
        return Tile(
            entry.label, entry.icon, entry.builtIn?.glyph,
            verified = pkg != null && KnownApps.works(pkg),
            broken = pkg != null && KnownApps.isBroken(pkg),
            partial = pkg != null && KnownApps.isPartial(pkg),
            onLongClick = id?.let { { navigator.showAppMenu(it) } },
        ) { openApp(entry, id?.let { running[it] }, navigator, frame) }
    }

    // Gearslip's own apps first, then everything installed, then Settings last where it's expected.
    val builtIns = all.filter { it.builtIn != null && (it.builtIn !== BuiltInApps.VehicleData || showVehicleData) }
    val tiles = builtIns.filter { it.builtIn !== BuiltInApps.Settings }.map(::tileOf) +
        all.filter { it.builtIn == null }.map(::tileOf) +
        builtIns.filter { it.builtIn === BuiltInApps.Settings }.map(::tileOf)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tiles) { AppTile(it) }
        if (tiles.any { it.verified || it.broken || it.partial }) {
            // Folded away behind one button: it's reference, read once, not something every
            // visit to the launcher needs spelled out under the grid.
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GsButton(onClick = { showBadgeKey = !showBadgeKey }, tone = GsTone.Neutral) {
                        Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (showBadgeKey) "Hide badge key" else "What the badges mean")
                    }
                    if (showBadgeKey) {
                        BadgeLegend(
                            verified = tiles.any { it.verified },
                            partial = tiles.any { it.partial },
                            broken = tiles.any { it.broken },
                        )
                    }
                }
            }
        }
    }
}

/** Says what each badge on a tile means, once, under the grid - only the ones actually in use. */
@Composable
private fun BadgeLegend(verified: Boolean, partial: Boolean, broken: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (verified) LegendRow(size = 16.dp, badge = { VerifiedBadge(it) }, text = "Tested and working with Gearslip")
        if (partial) LegendRow(size = 16.dp, badge = { PartialBadge(it) }, text = "Runs, but not cleanly, with Gearslip")
        if (broken) LegendRow(size = 16.dp, badge = { BrokenBadge(it) }, text = "Doesn't work with Gearslip yet")
    }
}

@Composable
private fun LegendRow(size: androidx.compose.ui.unit.Dp, badge: @Composable (androidx.compose.ui.unit.Dp) -> Unit, text: String) {
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        badge(size)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A green disc with a white check: the same mark on every tile and in the legend. */
@Composable
private fun VerifiedBadge(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(Color(0xFF43A047), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, contentDescription = "Tested and working", tint = Color.White, modifier = Modifier.size(size * 0.66f))
    }
}

/** A red disc with a white X: marks a tile known not to work with Gearslip yet. */
@Composable
private fun BrokenBadge(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(Color(0xFFD32F2F), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Doesn't work with Gearslip", tint = Color.White, modifier = Modifier.size(size * 0.66f))
    }
}

/** A yellow disc with a warning mark: neither the check nor the X, since this app runs but isn't clean about it. */
@Composable
private fun PartialBadge(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(Color(0xFFF9A825), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = "Works, but not cleanly, with Gearslip", tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(tile: Tile) {
    val shape = MaterialTheme.shapes.extraLarge
    // A long press opens the app's menu (pin, close) - the same gesture everywhere apps are
    // shown, here and on the nav bar.
    GsIconBox(
        onClick = tile.onClick,
        onLongClick = tile.onLongClick,
        modifier = Modifier.height(120.dp),
        colors = GsColors(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant),
        shape = shape,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when {
                    tile.icon != null -> Image(tile.icon, contentDescription = null, modifier = Modifier.size(52.dp))
                    tile.glyph != null -> Icon(tile.glyph, contentDescription = null, modifier = Modifier.size(52.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    tile.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tile.verified) {
                VerifiedBadge(24.dp, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            if (tile.broken) {
                BrokenBadge(24.dp, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            if (tile.partial) {
                PartialBadge(24.dp, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        }
    }
}
