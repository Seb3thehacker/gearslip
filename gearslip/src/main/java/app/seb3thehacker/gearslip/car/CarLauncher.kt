package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
    val pinned: Boolean = false,
    /** Null for the built-in tiles (Web, Screen sharing, Settings) - nothing to pin them as. */
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** A launcher app's data, kept apart from [Tile] so it can be cached without capturing click state. */
internal class Entry(
    val label: String,
    val icon: ImageBitmap?,
    val template: TemplateApp? = null,
    val media: MediaApp? = null,
) {
    /** What a pin or a nav-bar shortcut remembers this entry as; null for nothing pinnable. */
    val componentId: String? get() = (template?.component ?: media?.component)?.flattenToString()
}

/**
 * The installed car and media apps with their icons. Scanning the package manager and decoding
 * icons is slow, so [Prefetch] does it ahead of time and again at the start of each car session,
 * which is when new installs are picked up.
 */
internal object LauncherCache {
    @Volatile var entries: List<Entry>? = null

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
        return (nav + media).sortedBy { it.label.lowercase() }.also { entries = it }
    }
}

/** Rescans the car and media apps now, replacing the cached list; the launcher keeps showing the old one meanwhile. */
fun warmLauncherApps(context: Context) { LauncherCache.load(context, force = true) }

/**
 * Connects or opens [entry] - what tapping its tile does, shared with the nav bar's pinned
 * shortcuts and its open-app icon so there is exactly one place that knows how to launch either
 * kind of app.
 */
internal fun launchEntry(entry: Entry, navigator: CarNavigator, frame: CarEnvironment.Frame) {
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
}

/** The app launcher: Web and Screen sharing, every car app the phone has, then Settings, as an icon grid. */
@Composable
fun CarLauncher() {
    val context = LocalContext.current
    val navigator = LocalCarNavigator.current
    val frame by CarEnvironment.frame.collectAsState()

    // Painted from the cache at once; scanned only if [Prefetch] has not got there first.
    val installed by produceState(initialValue = LauncherCache.entries ?: emptyList(), context) {
        value = LauncherCache.entries ?: withContext(Dispatchers.IO) { LauncherCache.load(context) }
    }

    val pinned by CarSettings.pinnedApps.collectAsState()
    val experimentalFeatures by CarSettings.experimentalFeaturesEnabled.collectAsState()
    val carSensorsEnabled by CarSettings.carSensorsEnabled.collectAsState()

    val leadingTiles = buildList {
        add(Tile("Web", glyph = Icons.Filled.Search) { navigator.open("web") })
        add(Tile("Screen sharing", glyph = Icons.Filled.Share) { navigator.open("phone") })
        add(Tile("Phone", glyph = Icons.Filled.Call) { navigator.open("dialer") })
        // Experimental: raw, unverified sensor labels - see CarSensors.
        if (experimentalFeatures && carSensorsEnabled) {
            add(Tile("Vehicle data", glyph = Icons.Filled.Info) { navigator.vehicleData() })
        }
    }

    val tiles = leadingTiles + installed.map { entry ->
        val pkg = entry.template?.component?.packageName ?: entry.media?.component?.packageName
        val id = entry.componentId
        Tile(
            entry.label, entry.icon,
            verified = pkg != null && KnownApps.works(pkg),
            broken = pkg != null && KnownApps.isBroken(pkg),
            partial = pkg != null && KnownApps.isPartial(pkg),
            pinned = id != null && id in pinned,
            onLongClick = id?.let { { CarSettings.togglePin(it) } },
        ) { launchEntry(entry, navigator, frame) }
    } + listOf(
        Tile("Settings", glyph = Icons.Filled.Settings) { navigator.settings() },
    )

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tiles) { AppTile(it) }
        if (tiles.any { it.verified || it.broken || it.partial }) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BadgeLegend(
                    verified = tiles.any { it.verified },
                    partial = tiles.any { it.partial },
                    broken = tiles.any { it.broken },
                )
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
    Surface(
        modifier = Modifier
            .height(120.dp)
            .let { if (tile.pinned) it.border(2.dp, MaterialTheme.colorScheme.primary, shape) else it }
            // combinedClickable, not Surface's own onClick, so a long press can mean something
            // different from a tap - pinning is deliberately the same gesture everywhere apps
            // are shown (here and, once pinned, the shortcut itself on the nav bar).
            .combinedClickable(onClick = tile.onClick, onLongClick = tile.onLongClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
