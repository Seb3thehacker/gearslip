package app.seb3thehacker.gearslip.car

import androidx.compose.runtime.remember
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.Pane
import androidx.car.app.model.Row as CarRow
import androidx.car.app.model.Template
import app.seb3thehacker.gearslip.host.click
import app.seb3thehacker.gearslip.host.color
import app.seb3thehacker.gearslip.host.image
import app.seb3thehacker.gearslip.host.isDrawable
import app.seb3thehacker.gearslip.host.standardType
import app.seb3thehacker.gearslip.host.text

/**
 * The pieces every template is built from.
 *
 * The Car App Library's templates are combinations of a small set of parts - a header, an
 * action strip, a list of rows, a grid of tiles - so the host only has to decide once how each
 * part looks, and each template is then mostly an arrangement of them.
 */

// --- surfaces and text -------------------------------------------------------------------------

/**
 * The app keeps drawing its map across the whole surface - the visible area we report tells it
 * where to put its own markers, not where to stop painting. So anything the host lays over the
 * map is opaque by default, or the map reads through it.
 */
@Composable
internal fun ChromeSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    alpha: Float = 1f,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = alpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape,
        modifier = modifier,
        content = content,
    )
}

@Composable
internal fun UnsupportedChrome(template: Template, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = Alignment.Center) {
        ChromeSurface {
            Text(
                "${template.javaClass.simpleName} isn't drawn yet",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * A template's header: a back or app action on the left, a title, and the app's own actions on
 * the right. Templates carry this either as a [Header] or as the older title-plus-action pair.
 */
@Composable
internal fun HeaderBar(
    title: String,
    startAction: Action? = null,
    endActions: List<Action> = emptyList(),
    actionStrip: ActionStrip? = null,
) {
    val end = (endActions + actionStrip?.actions.orEmpty()).filter { it.isDrawable() }
    if (title.isEmpty() && startAction == null && end.isEmpty()) return

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        startAction?.takeIf { it.isDrawable() }?.let { ActionButton(it) }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        end.forEach { ActionButton(it) }
    }
}

/** Reads a header whichever way the template carries it. */
internal fun headerTitle(header: Header?, fallback: String): String =
    header?.title.text().ifEmpty { fallback }

// --- actions ------------------------------------------------------------------------------------

/**
 * Both strips share the right edge, where a hand reaches from the driver's seat: the app's own
 * actions in a row along the top, its map controls stacked under them. Keeping them in one
 * column is what stops them colliding on a screen only 480px tall.
 */
@Composable
internal fun RightEdgeControls(
    actionStrip: ActionStrip?,
    mapActionStrip: ActionStrip?,
    modifier: Modifier = Modifier,
) {
    val actions = actionStrip?.actions.orEmpty().filter { it.isDrawable() }
    val mapActions = mapActionStrip?.actions.orEmpty().filter { it.isDrawable() }
    if (actions.isEmpty() && mapActions.isEmpty()) return

    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { ActionButton(it) }
            }
        }
        mapActions.forEach { ActionButton(it) }
    }
}

@Composable
internal fun ActionRow(actions: List<Action>, modifier: Modifier = Modifier) {
    val drawable = actions.filter { it.isDrawable() }
    if (drawable.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        drawable.forEach { ActionButton(it) }
    }
}

@Composable
internal fun ActionButton(action: Action) {
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    val title = action.title.text()
    val background = action.backgroundColor.color(dark, Color.Black.copy(alpha = 0.6f))
    val hasGlyph = action.icon != null || action.type != Action.TYPE_CUSTOM

    Surface(
        color = background,
        contentColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.clickable(enabled = action.isEnabled) {
            action.onClickDelegate.click("action")
        },
    ) {
        Row(
            Modifier.padding(horizontal = if (title.isEmpty()) 10.dp else 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasGlyph) CarGlyph(action.icon, Modifier.size(24.dp), standardFor(action))
            if (title.isNotEmpty()) {
                if (hasGlyph) Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** A standard Action carries no icon of its own - the host is expected to supply the glyph. */
private fun standardFor(action: Action): Int? = when (action.type) {
    Action.TYPE_BACK -> CarIcon.TYPE_BACK
    Action.TYPE_PAN -> CarIcon.TYPE_PAN
    Action.TYPE_APP_ICON -> CarIcon.TYPE_APP_ICON
    else -> null
}

// --- icons ----------------------------------------------------------------------------------

/**
 * Draws an icon the app described, falling back to our own glyph for the standard types the
 * host owns. A custom icon that fails to load leaves the space empty rather than a placeholder,
 * since a wrong glyph on a map control is worse than none.
 */
@Composable
internal fun CarGlyph(icon: CarIcon?, modifier: Modifier = Modifier, standard: Int? = null) {
    val context = LocalContext.current
    val bitmap = icon.image(context)
    if (bitmap != null) {
        // An icon that names a tint (DEFAULT included) expects the host to colour it for the
        // surface it sits on; without this a white glyph vanishes on a light theme.
        val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
        val content = LocalContentColor.current
        val white = remember(bitmap) { bitmap.isPlainWhite() }
        // Untinted white artwork is a template-app habit from dark surfaces; it is coloured like
        // text so it stays readable on a light one.
        val tint = icon?.tint?.color(dark, content) ?: content.takeIf { white }
        Image(
            bitmap, contentDescription = null, modifier = modifier,
            colorFilter = tint?.let { ColorFilter.tint(it) },
        )
        return
    }
    val vector = when (standard ?: icon.standardType) {
        CarIcon.TYPE_BACK -> Icons.Filled.ArrowBack
        CarIcon.TYPE_ALERT, CarIcon.TYPE_ERROR -> Icons.Filled.Warning
        // No four-way pan glyph in material-icons-core; this is the closest honest stand-in.
        CarIcon.TYPE_PAN -> Icons.Filled.Menu
        else -> null
    } ?: return
    Icon(vector, contentDescription = null, modifier = modifier)
}

// --- lists, grids and panes ---------------------------------------------------------------------

@Composable
internal fun PaneTitle(title: String) {
    if (title.isEmpty()) return
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
internal fun ItemListColumn(
    single: ItemList?,
    sectioned: List<ItemList>? = null,
    modifier: Modifier = Modifier,
) {
    val rows = buildList {
        single?.items?.filterIsInstance<CarRow>()?.let(::addAll)
        sectioned?.forEach { list -> addAll(list.items.filterIsInstance<CarRow>()) }
    }
    if (rows.isEmpty()) {
        val message = single?.noItemsMessage.text()
        if (message.isNotEmpty()) Text(message, Modifier.padding(14.dp))
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 8.dp)) {
        items(rows) { RowItem(it) }
    }
}

/** A grid of large tappable tiles - what apps use for a home screen beside the map. */
@Composable
internal fun GridItems(list: ItemList?, modifier: Modifier = Modifier) {
    val items = list?.items?.filterIsInstance<GridItem>().orEmpty()
    if (items.isEmpty()) return
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items) { item ->
            Column(
                Modifier
                    .clickable { item.onClickDelegate.click("grid item") }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CarGlyph(item.image, Modifier.size(36.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    item.title.text(),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = item.text.text()
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PaneRows(pane: Pane?, modifier: Modifier = Modifier) {
    val rows = pane?.rows.orEmpty()
    Column(modifier) {
        rows.forEach { RowItem(it) }
        ActionRow(
            pane?.actions.orEmpty(),
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
internal fun RowItem(row: CarRow) {
    val clickable = row.onClickDelegate != null && row.isEnabled
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (clickable) it.clickable { row.onClickDelegate.click("row") } else it }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.image?.let {
            CarGlyph(it, Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                row.title.text(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.texts.orEmpty().take(2).forEach { line ->
                Text(
                    line.text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ActionRow(row.actions.orEmpty())
    }
}

internal fun Color.luminanceIsDark(): Boolean =
    (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f


/** True for a glyph drawn entirely in white (any transparency): a mask, not a picture. */
private fun androidx.compose.ui.graphics.ImageBitmap.isPlainWhite(): Boolean {
    val pixels = IntArray(width * height)
    readPixels(pixels)
    var seen = 0
    for (pixel in pixels) {
        val alpha = pixel ushr 24
        if (alpha < 40) continue
        seen++
        if ((pixel shr 16 and 0xFF) < 225 || (pixel shr 8 and 0xFF) < 225 || (pixel and 0xFF) < 225) return false
    }
    return seen > 0
}
