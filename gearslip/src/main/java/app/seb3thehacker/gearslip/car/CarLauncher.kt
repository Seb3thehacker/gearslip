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
 * icons is slow, so it happens once per car session; [invalidate] is called when one starts, which
 * is the only time new installs are picked up.
 */
private object LauncherCache {
    @Volatile var entries: List<Entry>? = null

    fun invalidate() { entries = null }

    fun load(context: Context): List<Entry> {
        fun iconOf(pkg: String) = runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap()
        }.getOrNull()

        val nav = CarAppCatalog.installed(context).map { Entry(it.label, iconOf(it.component.packageName), template = it) }
        val media = MediaCatalog.installed(context).map { Entry(it.label, iconOf(it.component.packageName), media = it) }
        return (nav + media).sortedBy { it.label.lowercase() }.also { entries = it }
    }
}

/** Forgets the scanned apps so the next launcher open rescans; call at the start of a car session. */
fun invalidateLauncherApps() = LauncherCache.invalidate()

/** The app launcher: Web and Screen sharing, every car app the phone has, then Settings, as an icon grid. */
@Composable
fun CarLauncher() {
    val context = LocalContext.current
    val navigator = LocalCarNavigator.current
    val frame by CarEnvironment.frame.collectAsState()

    // Scanned once per car session (see LauncherCache), not every time the launcher opens.
    val installed by produceState(initialValue = LauncherCache.entries ?: emptyList(), context) {
        value = LauncherCache.entries ?: withContext(Dispatchers.IO) { LauncherCache.load(context) }
    }

    val tiles = listOf(
        Tile("Web", glyph = Icons.Filled.Search) { navigator.open("web") },
        Tile("Screen sharing", glyph = Icons.Filled.Share) { navigator.open("phone") },
    ) + installed.map { entry ->
        Tile(entry.label, entry.icon) {
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
    }
}
