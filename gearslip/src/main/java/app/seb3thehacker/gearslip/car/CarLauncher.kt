package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
    val onClick: () -> Unit,
)

/** A launcher app's data, kept apart from [Tile] so it can be cached without capturing click state. */
private class Entry(
    val label: String,
    val icon: ImageBitmap?,
    val template: TemplateApp? = null,
    val media: MediaApp? = null,
)

/**
 * The installed car and media apps with their icons. Scanning the package manager and decoding
 * icons is slow, so [Prefetch] does it ahead of time and again at the start of each car session,
 * which is when new installs are picked up.
 */
private object LauncherCache {
    @Volatile var entries: List<Entry>? = null

    /** Returns the scan if there is one; [force] rescans. Synchronized so two callers share one scan. */
    @Synchronized
    fun load(context: Context, force: Boolean = false): List<Entry> {
        if (!force) entries?.let { return it }
        fun iconOf(pkg: String) = runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap()
        }.getOrNull()

        val nav = CarAppCatalog.installed(context).map { Entry(it.label, iconOf(it.component.packageName), template = it) }
        val media = MediaCatalog.installed(context).map { Entry(it.label, iconOf(it.component.packageName), media = it) }
        return (nav + media).sortedBy { it.label.lowercase() }.also { entries = it }
    }
}

/** Rescans the car and media apps now, replacing the cached list; the launcher keeps showing the old one meanwhile. */
fun warmLauncherApps(context: Context) { LauncherCache.load(context, force = true) }

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

    val tiles = listOf(
        Tile("Web", glyph = Icons.Filled.Search) { navigator.open("web") },
        Tile("Screen sharing", glyph = Icons.Filled.Share) { navigator.open("phone") },
    ) + installed.map { entry ->
        val pkg = entry.template?.component?.packageName ?: entry.media?.component?.packageName
        Tile(entry.label, entry.icon, verified = pkg != null && KnownApps.works(pkg)) {
            entry.template?.let { CarServices.connectNav(it, frame); navigator.home() }
            entry.media?.let { CarServices.openMedia(it); navigator.media() }
        }
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
        if (tiles.any { it.verified }) {
            item(span = { GridItemSpan(maxLineSpan) }) { VerifiedLegend() }
        }
    }
}

/** Says what the check mark on a tile means, once, under the grid. */
@Composable
private fun VerifiedLegend() {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VerifiedBadge(16.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            "Tested and working with Gearslip",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

@Composable
private fun AppTile(tile: Tile) {
    Surface(
        onClick = tile.onClick,
        modifier = Modifier.height(120.dp),
        shape = MaterialTheme.shapes.extraLarge,
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
        }
    }
}
