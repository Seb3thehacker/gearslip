package app.seb3thehacker.gearslip.car

import androidx.compose.runtime.remember
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import app.seb3thehacker.gearslip.host.selected
import app.seb3thehacker.gearslip.host.checkedChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * map is opaque, always, in the same one colour: a card that lets the map show through in places
 * reads as broken glass, not as a deliberate choice, and a driver has no way to tell which was
 * meant. There is no alpha knob here on purpose - every card, bar and strip in the template
 * chrome shares this surface, so nothing drawn over a map is ever see-through.
 */
@Composable
internal fun ChromeSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    shadowElevation: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        shadowElevation = shadowElevation,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape,
        modifier = modifier,
        content = content,
    )
}

/**
 * The one type scale for everything a templated app's screen draws. Apps hand the host plain
 * strings with no size of their own - the Car App Library deliberately keeps that decision out of
 * their hands - so without a shared scale, each template's text ends up whatever size felt right
 * when that template was written, and two apps' otherwise-identical rows read as different sizes
 * of importance. Every car chrome file should reach for one of these four rather than naming a
 * Material size directly.
 */
internal object ChromeType {
    /** The one number or word a card exists to show: distance to a turn, minutes left, a PIN. */
    val headline: androidx.compose.ui.text.TextStyle
        @Composable get() = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)

    /** A row's name, a header, a manoeuvre's instruction - the line the rest is supporting. */
    val title: androidx.compose.ui.text.TextStyle
        @Composable get() = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)

    /** Supporting detail: a road, an address, a message's body, a second line under a title. */
    val body: androidx.compose.ui.text.TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium

    /** The smallest text Gearslip draws over a map - still large enough to read while moving. */
    val label: androidx.compose.ui.text.TextStyle
        @Composable get() = MaterialTheme.typography.bodyLarge
}

@Composable
internal fun UnsupportedChrome(template: Template, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = Alignment.Center) {
        ChromeSurface {
            Text(
                "${template.javaClass.simpleName} isn't drawn yet",
                Modifier.padding(16.dp),
                style = ChromeType.title,
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
    // Beside a map the action strip already sits at the right edge, so the header must not repeat it;
    // and an app that lists the same action in two places should still see it once.
    val onMap = LocalMapEdgeActions.current
    val end = (endActions + if (onMap != null) emptyList() else actionStrip?.actions.orEmpty())
        .filter { it.isDrawable() && (onMap == null || it.identity() !in onMap) }
        .distinctBy { it.identity() }
    if (title.isEmpty() && startAction == null && end.isEmpty()) return

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        startAction?.takeIf { it.isDrawable() }?.let { ActionButton(it) }
        Text(
            title,
            style = ChromeType.title,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        end.forEach { ActionButton(it) }
    }
}

/**
 * Set inside the card beside a map to what the map's right edge already shows: the action strip is
 * drawn there rather than in the header, and the header leaves out anything the edge repeats.
 * Null anywhere else.
 */
internal val LocalMapEdgeActions = androidx.compose.runtime.compositionLocalOf<Set<String>?> { null }

/** What makes two actions the same button: what they do and what they look like. */
internal fun Action.identity(): String {
    val icon = icon?.icon
    val image = if (icon != null && icon.type == androidx.core.graphics.drawable.IconCompat.TYPE_RESOURCE) {
        "${icon.resPackage}:${icon.resId}"
    } else icon?.toString().orEmpty()
    return "$type|${title.text()}|$image"
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
    // A template that stops offering Pan must not leave the screen stuck in pan mode.
    val hasPan = (actions + mapActions).any { it.type == Action.TYPE_PAN }
    androidx.compose.runtime.LaunchedEffect(hasPan) { if (!hasPan) CarServices.nav.setPanMode(false) }

    // Faded out when the screen has been left alone; then they are only a memory of buttons, so
    // the first touch on one wakes them rather than pressing it.
    val awake = LocalMapControlsWake.current.awake
    val pan by CarServices.nav.panMode.collectAsState()
    val visible = awake || pan
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(if (visible) 150 else 500),
        label = "mapControls",
    )
    Column(
        modifier
            .alpha(alpha)
            .let { base ->
                if (visible) base else base.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { ActionButton(it) }
            }
        }
        mapActions.forEach { ActionButton(it) }
    }
}

/** [large] is the pane's main call to action: it shares the card's full width, taller, in bigger type. */
@Composable
internal fun ActionRow(actions: List<Action>, modifier: Modifier = Modifier, large: Boolean = false) {
    val drawable = actions.filter { it.isDrawable() }
    if (drawable.isEmpty()) return
    Row(if (large) modifier.fillMaxWidth() else modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        drawable.forEach { ActionButton(it, large = large, modifier = if (large) Modifier.weight(1f) else Modifier) }
    }
}

/** A row with nothing to show: no words, no picture, nothing to tap. */
private fun CarRow.isBlank(): Boolean =
    title.text().isBlank() && texts.orEmpty().all { it.text().isBlank() } && image == null &&
        toggle == null && onClickDelegate == null && actions.orEmpty().isEmpty()

/**
 * One look everywhere: solid, in the theme's own colors, whether the button sits on a card or over
 * the map. An app that names a color for its button (a green Start) keeps it.
 */
@Composable
internal fun ActionButton(action: Action, large: Boolean = false, modifier: Modifier = Modifier) {
    if (action.type == Action.TYPE_APP_ICON) {
        AppIconBadge()
        return
    }
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    val title = action.title.text()
    val panOn by CarServices.nav.panMode.collectAsState()
    val active = action.type == Action.TYPE_PAN && panOn
    val scheme = MaterialTheme.colorScheme
    val custom = action.backgroundColor?.type?.let { it != androidx.car.app.model.CarColor.TYPE_DEFAULT } == true
    val fallback = scheme.surfaceContainerHighest
    val background = if (active) scheme.primary else action.backgroundColor.color(dark, fallback)
    val content = when {
        active -> scheme.onPrimary
        custom -> Color.White
        else -> scheme.onSurface
    }
    val hasGlyph = action.icon != null || action.type != Action.TYPE_CUSTOM

    Surface(
        color = background,
        contentColor = content,
        shape = if (title.isEmpty()) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(if (large) 28.dp else 24.dp),
        modifier = modifier.clickable(enabled = action.isEnabled) {
            if (action.type == Action.TYPE_BACK) CarServices.nav.backPressed()
            else if (action.type == Action.TYPE_PAN) CarServices.nav.setPanMode(!panOn)
            else action.onClickDelegate?.click("action")
        },
    ) {
        if (large && title.isNotEmpty()) {
            // The label sits dead centre on the button; the icon hangs off the left edge, so the
            // pair's uneven visual weight can't pull the words off to one side.
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (hasGlyph) CarGlyph(action.icon, Modifier.size(30.dp).align(Alignment.CenterStart), standardFor(action))
                Text(title, style = ChromeType.title, maxLines = 1)
            }
        } else if (title.isEmpty()) {
            // Every icon-only button - back, settings, the map's zoom and locate - is the same size.
            androidx.compose.foundation.layout.Box(Modifier.size(ICON_BUTTON_SIZE), contentAlignment = Alignment.Center) {
                if (hasGlyph) CarGlyph(action.icon, Modifier.size(ICON_BUTTON_GLYPH), standardFor(action))
            }
        } else {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasGlyph) CarGlyph(action.icon, Modifier.size(24.dp), standardFor(action))
                if (hasGlyph) Spacer(Modifier.width(8.dp))
                Text(title, style = ChromeType.body, maxLines = 1)
            }
        }
    }
}

/**
 * A large action that fires on its own after five seconds, filling to show the countdown - for
 * the one button on a map-preview pane, where the app is proposing a single place or route and a
 * driver glancing at the car screen has already decided. Tapping it, same as any other button,
 * fires it at once; the fill is only ever a preview of what a touch already does.
 */
@Composable
internal fun AutoStartButton(action: Action, modifier: Modifier = Modifier) {
    var fraction by remember(action.identity()) { mutableStateOf(0f) }
    var fired by remember(action.identity()) { mutableStateOf(false) }
    fun fire() {
        if (fired) return
        fired = true
        action.onClickDelegate.click("auto-start")
    }
    androidx.compose.runtime.LaunchedEffect(action.identity()) {
        val steps = 60
        repeat(steps) { step ->
            kotlinx.coroutines.delay(AUTO_START_MS / steps)
            fraction = (step + 1) / steps.toFloat()
        }
        fire()
    }

    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    val custom = action.backgroundColor?.type?.let { it != androidx.car.app.model.CarColor.TYPE_DEFAULT } == true
    val background = action.backgroundColor.color(dark, MaterialTheme.colorScheme.primary)
    val content = if (custom) Color.White else MaterialTheme.colorScheme.onPrimary
    val hasGlyph = action.icon != null

    Surface(
        // An unfilled track, solid, not the button's own colour dimmed with alpha - so the map
        // behind never shows through however much of the five seconds is left.
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = content,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().clickable(enabled = action.isEnabled) { fire() },
    ) {
        androidx.compose.foundation.layout.Box {
            // The countdown itself: a plain fill sweeping left to right underneath the label.
            // matchParentSize(), not fillMaxHeight() - this Box sits beside a label Box that
            // wraps its own content, and the outer Box sizes itself to its tallest child; a
            // plain fillMaxHeight() here has nothing bounded to fill up to but the whole screen,
            // which is what was stretching the whole button that tall. matchParentSize() instead
            // takes whatever size the label ends up being, without itself voting on what that is.
            androidx.compose.foundation.layout.Box(Modifier.matchParentSize()) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(background),
                )
            }
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (hasGlyph) CarGlyph(action.icon, Modifier.size(24.dp).align(Alignment.CenterStart))
                Text(
                    action.title.text().ifEmpty { "Start" },
                    style = ChromeType.title,
                    maxLines = 1,
                )
            }
        }
    }
}

private const val AUTO_START_MS = 5_000L

/** The connected app's own launcher icon, which is what the app-icon action stands for. */
@Composable
private fun AppIconBadge() {
    val context = LocalContext.current
    val pkg = CarServices.nav.appPackage
    val icon = remember(pkg) {
        pkg?.let {
            runCatching {
                context.packageManager.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    } ?: return
    Image(icon, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
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
        style = ChromeType.title,
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
        if (message.isNotEmpty()) Text(message, Modifier.padding(14.dp), style = ChromeType.body)
        return
    }
    // A list the app made selectable is a single-choice list: one option chosen, shown as a radio.
    val onSelected = single?.onSelectedDelegate
    val chosen = single?.selectedIndex ?: -1
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 8.dp)) {
        itemsIndexed(rows) { index, row ->
            RowItem(row, onSelected?.let { RowSelection(index == chosen) { it.selected(index) } })
        }
    }
}

/** A grid of large tappable tiles - what apps use for a home screen beside the map. */
@Composable
internal fun GridItems(list: ItemList?, modifier: Modifier = Modifier) {
    val items = list?.items?.filterIsInstance<GridItem>().orEmpty()
    if (items.isEmpty()) return
    LazyVerticalGrid(
        columns = GridCells.Adaptive(84.dp),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
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
                    style = ChromeType.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = item.text.text()
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = ChromeType.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * [autoStart] is for a pane floating over a map - a place or a route the app is proposing - where
 * a single action counts itself down rather than waiting to be pressed, on the theory that a
 * driver glancing at the car screen has already decided. It never applies to more than one
 * action: a pane offering a real choice (Start vs. something else) always waits to be tapped.
 */
@Composable
internal fun PaneRows(pane: Pane?, modifier: Modifier = Modifier, autoStart: Boolean = false) {
    // Some apps pad a pane with a row of blank strings; drawn, it is just a gap.
    val rows = pane?.rows.orEmpty().filterNot { it.isBlank() }
    val actions = pane?.actions.orEmpty().filter { it.isDrawable() }
    Column(modifier) {
        rows.forEach { RowItem(it, large = true) }
        if (autoStart && actions.size == 1) {
            AutoStartButton(actions.single(), Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        } else {
            ActionRow(actions, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), large = true)
        }
    }
}

@Composable
internal fun RowItem(row: CarRow, selection: RowSelection? = null, large: Boolean = false) {
    val toggle = row.toggle
    // Shown at once and corrected when the app answers with its own template, so the switch
    // never lags a tap; keyed on what the app last said so its answer wins.
    var checked by remember(toggle?.isChecked) { mutableStateOf(toggle?.isChecked == true) }
    val clickable = row.isEnabled && (toggle != null || selection != null || row.onClickDelegate != null)
    Row(
        Modifier
            .fillMaxWidth()
            .let {
                if (!clickable) it else it.clickable {
                    when {
                        toggle != null -> {
                            checked = !checked
                            toggle.onCheckedChangeDelegate.checkedChanged(checked)
                        }
                        selection != null -> selection.onPick()
                        else -> row.onClickDelegate.click("row")
                    }
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selection != null) {
            RadioButton(selected = selection.selected, onClick = null)
            Spacer(Modifier.width(12.dp))
        }
        row.image?.let {
            CarGlyph(it, Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        // large only changes how much room a row gets (more lines, a bigger icon above) - the
        // type itself is the same scale as every other row, so a pane's one row and a plain
        // list's tenth row read as the same kind of text.
        Column(Modifier.weight(1f)) {
            Text(
                row.title.text(),
                style = ChromeType.title,
                maxLines = if (large) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.texts.orEmpty().take(if (large) 3 else 2).forEach { line ->
                Text(
                    line.text(),
                    style = ChromeType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (large) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ActionRow(row.actions.orEmpty())
        if (toggle != null) {
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = null, enabled = row.isEnabled)
        }
    }
}

/** A row's place in a single-choice list: whether it is the chosen one, and what picking it does. */
internal class RowSelection(val selected: Boolean, val onPick: () -> Unit)

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

private val ICON_BUTTON_SIZE = 54.dp
private val ICON_BUTTON_GLYPH = 30.dp
