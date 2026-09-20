package app.seb3thehacker.gearslip.car

import android.graphics.Rect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.car.app.model.Template
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.TravelEstimate
import app.seb3thehacker.gearslip.host.color
import app.seb3thehacker.gearslip.host.display
import app.seb3thehacker.gearslip.host.remainingTime
import app.seb3thehacker.gearslip.host.text

/**
 * Draws the template an app handed us, over the surface it is drawing its map into.
 *
 * A templated app never draws its own chrome: it describes a screen in the Car App Library's
 * model types and every host renders those to suit its own screen. This is Gearslip's
 * rendering. It is deliberately laid out for a wide, short car display - controls to the
 * right where a hand reaches from the driver's seat, information to the left.
 *
 * Templates split into two kinds. The six map-backed ones here leave room for the app's own
 * drawing and lay chrome around it; everything else fills the screen and is drawn by
 * [ContentTemplate].
 */
@Composable
fun TemplateChrome(template: Template?, modifier: Modifier = Modifier) {
    when (template) {
        null -> Unit
        is NavigationTemplate -> NavigationChrome(template, modifier)
        is MapWithContentTemplate -> MapWithContentChrome(template, modifier)
        is MapTemplate -> MapChrome(template, modifier)
        is PlaceListNavigationTemplate -> PlaceListNavigationChrome(template, modifier)
        is RoutePreviewNavigationTemplate -> RoutePreviewChrome(template, modifier)
        is PlaceListMapTemplate -> PlaceListMapChrome(template, modifier)
        else -> ContentTemplate(template, modifier)
    }
}

/**
 * How much of the surface Gearslip's chrome covers, so the app can keep what matters clear of
 * it. Expressed as the visible rectangle within a [width] x [height] surface.
 *
 * A template that isn't map-backed covers the surface completely, and reporting an empty
 * rectangle is how the app is told to stop bothering.
 */
fun visibleAreaFor(template: Template?, width: Int, height: Int): Rect = when (template) {
    is NavigationTemplate -> Rect(0, 0, width, (height * (1f - ESTIMATE_FRACTION)).toInt())
    is MapWithContentTemplate, is MapTemplate, is PlaceListNavigationTemplate,
    is RoutePreviewNavigationTemplate, is PlaceListMapTemplate,
    -> Rect((width * CONTENT_PANE_FRACTION).toInt(), 0, width, height)
    null -> Rect(0, 0, width, height)
    else -> Rect(0, 0, 0, 0)
}

// --- navigation ------------------------------------------------------------------------------

@Composable
private fun NavigationChrome(template: NavigationTemplate, modifier: Modifier) {
    Box(modifier.fillMaxWidth()) {
        (template.navigationInfo as? RoutingInfo)?.let {
            RoutingCard(it, Modifier.align(Alignment.TopStart).padding(12.dp).width(320.dp))
        }
        RightEdgeControls(
            template.actionStrip,
            template.mapActionStrip,
            Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
        template.destinationTravelEstimate?.let {
            TravelEstimateBar(it, Modifier.align(Alignment.BottomStart))
        }
    }
}

/** The manoeuvre card: what to do next, how far away it is, and onto what road. */
@Composable
private fun RoutingCard(info: RoutingInfo, modifier: Modifier = Modifier) {
    if (info.isLoading) {
        ChromeSurface(modifier) {
            Text("Finding a route…", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    val step = info.currentStep ?: return
    ChromeSurface(modifier) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CarGlyph(step.maneuver?.icon, Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val distance = info.currentDistance.display()
                    if (distance.isNotEmpty()) {
                        Text(
                            distance,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        step.cue.text(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val road = step.road.text()
            if (road.isNotEmpty()) {
                Text(
                    road,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            info.nextStep?.let { next ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "then",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    CarGlyph(next.maneuver?.icon, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        next.cue.text(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Time and distance left, along the bottom where it stays out of the map's way. */
@Composable
private fun TravelEstimateBar(estimate: TravelEstimate, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminanceIsDark()
    val time = remainingTime(estimate.remainingTimeSeconds)
    val distance = estimate.remainingDistance.display()
    val trip = estimate.tripText.text()
    if (time.isEmpty() && distance.isEmpty() && trip.isEmpty()) return

    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (time.isNotEmpty()) {
                Text(
                    time,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = estimate.remainingTimeColor.color(dark, Color.White),
                )
            }
            if (distance.isNotEmpty()) {
                Text(
                    distance,
                    style = MaterialTheme.typography.titleMedium,
                    color = estimate.remainingDistanceColor.color(dark, Color.White),
                )
            }
            if (trip.isNotEmpty()) {
                Text(trip, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }
}

// --- map with a side pane ----------------------------------------------------------------------

/**
 * The shape every remaining map-backed template shares: a pane down the left, the app's map
 * showing through on the right, and both action strips stacked at the right edge.
 */
@Composable
private fun MapPaneLayout(
    modifier: Modifier,
    actionStrip: androidx.car.app.model.ActionStrip?,
    mapActionStrip: androidx.car.app.model.ActionStrip?,
    pane: @Composable (Modifier) -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        pane(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(CONTENT_PANE_FRACTION),
        )
        RightEdgeControls(
            actionStrip,
            mapActionStrip,
            Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
    }
}

@Composable
private fun MapWithContentChrome(template: MapWithContentTemplate, modifier: Modifier) {
    MapPaneLayout(
        modifier,
        template.actionStrip,
        template.mapController?.mapActionStrip,
    ) { paneModifier ->
        ContentTemplate(template.contentTemplate, paneModifier)
    }
}

@Composable
private fun MapChrome(template: MapTemplate, modifier: Modifier) {
    MapPaneLayout(
        modifier,
        template.actionStrip,
        template.mapController?.mapActionStrip,
    ) { paneModifier ->
        ChromeSurface(paneModifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {
            Column {
                HeaderBar(
                    headerTitle(template.header, ""),
                    template.header?.startHeaderAction,
                    template.header?.endHeaderActions.orEmpty(),
                )
                if (template.pane != null) PaneRows(template.pane)
                else ItemListColumn(template.itemList)
            }
        }
    }
}

@Composable
private fun PlaceListNavigationChrome(template: PlaceListNavigationTemplate, modifier: Modifier) {
    MapPaneLayout(modifier, template.actionStrip, template.mapActionStrip) { paneModifier ->
        ListPane(
            headerTitle(template.header, template.title.text()),
            template.header?.startHeaderAction ?: template.headerAction,
            template.isLoading,
            template.itemList,
            paneModifier,
        )
    }
}

@Composable
private fun PlaceListMapChrome(template: PlaceListMapTemplate, modifier: Modifier) {
    MapPaneLayout(modifier, template.actionStrip, null) { paneModifier ->
        ListPane(
            template.title.text(),
            template.headerAction,
            template.isLoading,
            template.itemList,
            paneModifier,
        )
    }
}

/** Route choices down the side, with the app's "start" action pinned under them. */
@Composable
private fun RoutePreviewChrome(template: RoutePreviewNavigationTemplate, modifier: Modifier) {
    MapPaneLayout(modifier, template.actionStrip, template.mapActionStrip) { paneModifier ->
        ChromeSurface(paneModifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {
            Column(Modifier.fillMaxHeight()) {
                HeaderBar(
                    headerTitle(template.header, template.title.text()),
                    template.header?.startHeaderAction ?: template.headerAction,
                    template.header?.endHeaderActions.orEmpty(),
                )
                Box(Modifier.weight(1f)) {
                    if (template.isLoading) {
                        Text("Finding routes…", Modifier.padding(14.dp))
                    } else {
                        ItemListColumn(template.itemList)
                    }
                }
                template.navigateAction?.let {
                    ActionRow(listOf(it), Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ListPane(
    title: String,
    startAction: androidx.car.app.model.Action?,
    loading: Boolean,
    itemList: androidx.car.app.model.ItemList?,
    modifier: Modifier,
) {
    ChromeSurface(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {
        Column {
            HeaderBar(title, startAction)
            if (loading) Text("Loading…", Modifier.padding(14.dp))
            else ItemListColumn(itemList)
        }
    }
}

/** Fraction of the surface the content pane covers beside a map. */
private const val CONTENT_PANE_FRACTION = 0.42f

/** Fraction of the surface the travel-estimate bar covers along the bottom. */
private const val ESTIMATE_FRACTION = 0.14f
