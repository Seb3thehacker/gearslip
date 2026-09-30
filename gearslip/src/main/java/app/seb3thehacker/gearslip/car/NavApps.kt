package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.car.theme.GsButton
import app.seb3thehacker.gearslip.car.theme.GsTone
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.media.CarMedia

/** What a built-in app's id carries in a pin, so it can't collide with an installed app's component. */
internal const val BUILT_IN_PREFIX = "gearslip:"

/** One of Gearslip's own apps: a screen, with nothing running behind it once you leave. */
internal class BuiltInApp(
    val id: String,
    val label: String,
    val glyph: ImageVector,
    val screen: CarScreen,
    val open: (CarNavigator) -> Unit,
)

internal object BuiltInApps {
    val Web = BuiltInApp("web", "Web", Icons.Filled.Search, CarScreen.App("web")) { it.open("web") }
    val ScreenSharing = BuiltInApp("phone", "Screen sharing", Icons.Filled.Share, CarScreen.App("phone")) { it.open("phone") }
    val Phone = BuiltInApp("dialer", "Phone", Icons.Filled.Call, CarScreen.App("dialer")) { it.open("dialer") }
    val VehicleData = BuiltInApp("vehicle", "Vehicle data", Icons.Filled.Info, CarScreen.VehicleData) { it.vehicleData() }
    val Settings = BuiltInApp("settings", "Settings", Icons.Filled.Settings, CarScreen.Settings) { it.settings() }

    val all = listOf(Web, ScreenSharing, Phone, VehicleData, Settings)
    val entries: List<Entry> = all.map { Entry(it.label, icon = null, builtIn = it) }
}

/** Vehicle data is experimental: raw, unverified sensor labels (see [CarSensors]). */
@Composable
internal fun rememberVehicleDataShown(): Boolean {
    val experimental by CarSettings.experimentalFeaturesEnabled.collectAsState()
    val sensors by CarSettings.carSensorsEnabled.collectAsState()
    return experimental && sensors
}

/**
 * Where an open app lives - decides where tapping it goes and how it closes. Gearslip's own
 * apps have no connection behind them, so [SCREEN] is simply "on screen right now".
 */
internal enum class RunningSlot { NAV, BROWSE, MEDIA, SCREEN }

/**
 * Apps open right now, as component id to where they live, in the order the nav bar shows them:
 * the map, then a browsed templated app, then the media app, then whichever built-in app is on
 * screen. The map and a browsed app count from the moment they start connecting until they're
 * closed. The media app counts while it has something loaded to play - the same rule the nav
 * bar's player follows, so the two always show up together - or while its own screen is up.
 */
@Composable
internal fun rememberRunningApps(): Map<String, RunningSlot> {
    val navStatus by CarServices.nav.status.collectAsState()
    val browseStatus by CarServices.browse.status.collectAsState()
    val mediaApp by CarServices.mediaApp.collectAsState()
    val mediaPhase by CarServices.media.phase.collectAsState()
    val now by CarServices.media.now.collectAsState()
    val current = LocalCarNavigator.current.current

    fun CarAppConnection.liveId(phase: CarAppConnection.Phase) =
        connectedComponent?.flattenToString()?.takeIf {
            phase == CarAppConnection.Phase.BINDING ||
                phase == CarAppConnection.Phase.HANDSHAKE ||
                phase == CarAppConnection.Phase.RUNNING
        }

    return buildMap {
        CarServices.nav.liveId(navStatus.phase)?.let { put(it, RunningSlot.NAV) }
        CarServices.browse.liveId(browseStatus.phase)?.let { put(it, RunningSlot.BROWSE) }
        val mediaLive = when (mediaPhase) {
            CarMedia.Phase.CONNECTING -> true
            CarMedia.Phase.READY -> now.isActive || current == CarScreen.Media
            else -> false
        }
        mediaApp?.component?.flattenToString()?.takeIf { mediaLive }?.let { put(it, RunningSlot.MEDIA) }
        BuiltInApps.all.firstOrNull { it.screen == current }?.let { put(BUILT_IN_PREFIX + it.id, RunningSlot.SCREEN) }
        (current as? CarScreen.Messages)?.let { put(MESSAGING_PREFIX + it.packageName, RunningSlot.SCREEN) }
    }
}

/** The screen an open app is showing on, so its nav bar icon can stay pressed in while it's up. */
internal fun RunningSlot.isShowing(current: CarScreen): Boolean = when (this) {
    RunningSlot.NAV -> current == CarScreen.Home
    RunningSlot.BROWSE -> current == CarScreen.Browse
    RunningSlot.MEDIA -> current == CarScreen.Media
    RunningSlot.SCREEN -> true
}

/**
 * Brings an app up: back to its screen if it's already open - never a reconnect, which would
 * throw away the route or restart the session - otherwise a fresh launch.
 */
internal fun openApp(entry: Entry, slot: RunningSlot?, navigator: CarNavigator, frame: CarEnvironment.Frame) {
    when (slot) {
        RunningSlot.NAV -> navigator.home()
        RunningSlot.BROWSE -> navigator.browse(entry.label)
        RunningSlot.MEDIA -> navigator.media()
        RunningSlot.SCREEN -> Unit
        null -> launchEntry(entry, navigator, frame)
    }
}

internal fun closeApp(slot: RunningSlot, navigator: CarNavigator) {
    when (slot) {
        RunningSlot.NAV -> CarServices.stopNav()
        RunningSlot.BROWSE -> CarServices.stopBrowse()
        RunningSlot.MEDIA -> CarServices.closeMedia()
        RunningSlot.SCREEN -> Unit
    }
    if (slot != RunningSlot.NAV && slot.isShowing(navigator.current)) navigator.back()
}

/**
 * What a long press on an app offers - pin it to or unpin it from the nav bar, and close it if
 * it's open - as a card in the middle of the screen with car-sized buttons, over a scrim that
 * dismisses it. Drawn in the car UI's own layout rather than a system popup window, which the
 * car's virtual display has no reason to host well.
 */
@Composable
internal fun AppMenu(navigator: CarNavigator, running: Map<String, RunningSlot>) {
    val id = navigator.appMenu ?: return
    val entry = findEntry(id)
    if (entry == null) {
        navigator.dismissAppMenu()
        return
    }
    val pinned by CarSettings.pinnedApps.collectAsState()
    val isPinned = id in pinned
    val barFull = !isPinned && pinned.size >= CarSettings.MAX_PINNED
    val slot = running[id]

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = navigator::dismissAppMenu,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            // Swallows taps on the card itself, so only the scrim around it dismisses.
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    entry.icon?.let { Image(it, null, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) }
                    entry.builtIn?.let { Icon(it.glyph, null, Modifier.size(36.dp)) }
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }
                GsButton(
                    onClick = {
                        CarSettings.togglePin(id)
                        navigator.dismissAppMenu()
                    },
                    tone = GsTone.Tonal,
                    enabled = !barFull,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(
                        when {
                            isPinned -> "Unpin from nav bar"
                            barFull -> "Nav bar is full (${CarSettings.MAX_PINNED} pinned)"
                            else -> "Pin to nav bar"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (slot != null) {
                    GsButton(
                        onClick = {
                            closeApp(slot, navigator)
                            navigator.dismissAppMenu()
                        },
                        tone = GsTone.Danger,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("Close app", style = MaterialTheme.typography.titleMedium) }
                }
                GsButton(
                    onClick = navigator::dismissAppMenu,
                    tone = GsTone.Neutral,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Cancel", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}
