package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.MediaArt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.notify.CarNotifications
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The whole car screen: the current screen on top, the nav bar along the bottom.
 *
 * Colours follow the phone's theme (Material You). The size setting scales everything by
 * changing the density, so text, icons and touch targets grow together.
 */
@Composable
fun CarUi() {
    val scale by CarSettings.scale.collectAsState()
    val phoneDark by CarEnvironment.phoneDark.collectAsState()
    val appTheme by CarSettings.appTheme.collectAsState()
    val mediaApp by CarServices.mediaApp.collectAsState()
    val darkOutside by CarEnvironment.darkOutside.collectAsState()
    val appContext = LocalContext.current.applicationContext
    val dark = when (appTheme) {
        AppTheme.PHONE -> phoneDark
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    val colors = if (dark) dynamicDarkColorScheme(appContext) else dynamicLightColorScheme(appContext)
    val base = LocalDensity.current
    val insets by CarEnvironment.insets.collectAsState()
    val navigator = remember { CarNavigator(CarSettings.openOnConnect.value) }
    val controlsWake = remember { MapControlsWake() }
    LaunchedEffect(controlsWake) {
        while (true) {
            delay(250)
            controlsWake.tick()
        }
    }
    remember(appContext) { Prefetch.warm(appContext, force = true); CarServices.init(appContext) }
    DisposableEffect(Unit) { onDispose { CarServices.shutdown() } }
    // The map follows the light outside, whatever the app's own theme is set to.
    LaunchedEffect(darkOutside) { CarServices.nav.pushConfiguration() }

    CompositionLocalProvider(
        LocalDensity provides Density(base.density * scale, base.fontScale),
        LocalCarNavigator provides navigator,
        LocalDarkOutside provides darkOutside,
        LocalMapControlsWake provides controlsWake,
    ) {
        MaterialTheme(colorScheme = colors) {
            Surface(
                Modifier
                    .fillMaxSize()
                    // Watches every touch without taking any: first in line, consumes nothing.
                    .pointerInput(controlsWake) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                controlsWake.touch()
                            }
                        }
                    },
                color = MaterialTheme.colorScheme.background,
            ) {
                // Insets are in video-frame pixels, so convert with the un-scaled density.
                val pad = with(base) {
                    PaddingValues(
                        start = insets.left.toDp(), top = insets.top.toDp(),
                        end = insets.right.toDp(), bottom = insets.bottom.toDp(),
                    )
                }
                // The calibration screen is the raw frame: no insets, no nav bar.
                val screen = navigator.current
                if (screen == CarScreen.App("calibrate")) {
                    CarApps.find("calibrate")?.content?.invoke()
                } else {
                    Column(Modifier.fillMaxSize().padding(pad)) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (screen) {
                                CarScreen.Home -> CarHome()
                                CarScreen.Apps -> CarLauncher()
                                CarScreen.Media -> mediaApp?.let { MediaScreen(it) { navigator.home() } }
                                CarScreen.Settings -> CarSettingsScreen()
                                CarScreen.Dashboard -> CarDashboardScreen()
                                CarScreen.Weather -> WeatherScreen()
                                is CarScreen.Notifications -> NotificationsScreen(screen.replyTo)
                                is CarScreen.App -> CarApps.find(screen.id)?.content?.invoke()
                            }
                            // The history screen is already showing them, so it needs no popup.
                            if (screen !is CarScreen.Notifications) {
                                NotificationPopup(Modifier.align(Alignment.TopCenter))
                            }
                        }
                        CarNavBar(navigator)
                    }
                }
            }
        }
    }
}

/**
 * Home and Apps on the left; now-playing pill in the middle; clock (tap for calendar and weather), phone battery and the
 * darkness signal on the right. Icon-only: a driver reads a glyph faster than a label, and it
 * keeps the bar short enough to leave the app underneath more room.
 */
@Composable
private fun CarNavBar(navigator: CarNavigator) {
    val battery by CarEnvironment.battery.collectAsState()
    val darkOutside by CarEnvironment.darkOutside.collectAsState()
    val now by rememberNow()

    // The home screen shows what's playing itself, unless it was minimised; everywhere else it lives in this bar.
    val showNowPlaying = navigator.current != CarScreen.Home || navigator.playerMinimised
    val weather by Weather.state.collectAsState()

    // One row, so nothing can sit on top of anything else: the player takes whatever width the
    // fixed items leave and shortens its title to fit.
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(Icons.Filled.Home, navigator.current == CarScreen.Home) { navigator.home() }
            Spacer(Modifier.width(6.dp))
            NavItem(MediaIcons.Apps, navigator.current == CarScreen.Apps) { navigator.apps() }

            Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (showNowPlaying) NavNowPlaying(navigator, selected = navigator.current == CarScreen.Media)
            }

            (weather as? WeatherState.Ready)?.data?.let { w ->
                NavChip(navigator.current == CarScreen.Weather, { navigator.weather() }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WeatherIcon(w.code, w.isDay, size = 28.dp)
                        Text("${w.temp}°", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            NavChip(navigator.current == CarScreen.Dashboard, { navigator.dashboard() }) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val phone = if (battery.percent >= 0) "${battery.percent}%${if (battery.charging) " charging" else ""}" else ""
                    val light = if (darkOutside) "Night" else "Day"
                    Text(
                        listOf(phone, light).filter { it.isNotEmpty() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            NavItem(
                Icons.Filled.Notifications, navigator.current is CarScreen.Notifications,
            ) { navigator.notifications() }
        }
    }
}

/** A tappable group in the bar that stays highlighted while its screen is open, like [NavItem]. */
@Composable
private fun NavChip(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun NavItem(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
    }
}

/** Wall-clock time, ticking on the minute: no reason to redraw (and re-encode) more often. */
@Composable
internal fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(60_000 - value % 60_000)
    }
}

/** Art, title and play/pause in the nav bar; tapping the art or title opens the full media screen. */
@Composable
private fun NavNowPlaying(navigator: CarNavigator, selected: Boolean, modifier: Modifier = Modifier) {
    val media = CarServices.media
    val phase by media.phase.collectAsState()
    val now by media.now.collectAsState()
    if (phase != CarMedia.Phase.READY || !now.isActive) return
    val context = LocalContext.current
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
    Row(
        Modifier.padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(50))
                .clickable { navigator.media() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Text(
                now.title.ifEmpty { "Nothing playing" },
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledIconButton(onClick = media::togglePlay, modifier = Modifier.size(44.dp)) {
            Icon(
                if (now.playing) MediaIcons.Pause else Icons.Filled.PlayArrow,
                if (now.playing) "Pause" else "Play",
                Modifier.size(26.dp),
            )
        }
        IconButton(onClick = { media.next() }, modifier = Modifier.size(44.dp)) {
            Icon(MediaIcons.Next, "Next", Modifier.size(28.dp))
        }
    }
    }
}
