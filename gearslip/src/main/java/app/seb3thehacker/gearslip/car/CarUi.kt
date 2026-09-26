package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Color
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
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.notify.CarNotifications
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
                        BreadcrumbBar(navigator)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (screen) {
                                CarScreen.Home -> CarHome()
                                CarScreen.Apps -> CarLauncher()
                                CarScreen.Media -> mediaApp?.let { MediaScreen(it) { navigator.back() } }
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
                            // Above the popup: a call is more urgent than any notification.
                            CallOverlay(Modifier.align(Alignment.TopCenter))
                        }
                        CarNavBar(navigator)
                    }
                }
            }
        }
    }
}

/**
 * "Home > Apps > Settings", like a file manager's address bar: hidden at Home itself (nothing to
 * show yet), and grows by one crumb for every screen pushed from there. The back arrow undoes one
 * step; a crumb jumps straight to it, dropping everything after - this is the only on-screen way
 * back at all, since a car touchscreen has no hardware button for it.
 */
@Composable
private fun BreadcrumbBar(navigator: CarNavigator) {
    val trail = navigator.trail
    if (trail.size <= 1) return
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(start = 2.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = navigator::back, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(20.dp))
        }
        Spacer(Modifier.width(2.dp))
        trail.forEachIndexed { index, crumb ->
            val isLast = index == trail.lastIndex
            Text(
                crumb.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (isLast) Modifier else Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { navigator.jumpTo(index) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
            if (!isLast) {
                Text(
                    "›",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

/**
 * Apps and open/pinned shortcuts on the left; now-playing pill against them; clock (tap for
 * calendar and weather), phone battery and the darkness signal on the right. Icon-only: a driver
 * reads a glyph faster than a label, and it keeps the bar short enough to leave the app underneath
 * more room.
 */
@Composable
private fun CarNavBar(navigator: CarNavigator) {
    val battery by CarEnvironment.battery.collectAsState()
    val now by rememberNow()
    val weather by Weather.state.collectAsState()
    val weatherData = (weather as? WeatherState.Ready)?.data

    // The one app that's actually open and not otherwise reachable from this bar: media has its
    // own pill already, so the only "open app" icon worth showing is whichever map is connected.
    val navStatus by CarServices.nav.status.collectAsState()
    val lastNav by CarSettings.lastNav.collectAsState()
    val openMapComponent = lastNav?.takeIf { navStatus.phase == CarAppConnection.Phase.RUNNING }

    val context = LocalContext.current
    val pinnedIds by CarSettings.pinnedApps.collectAsState()
    val entries by produceState(LauncherCache.entries ?: emptyList(), context) {
        value = LauncherCache.entries ?: withContext(Dispatchers.IO) { LauncherCache.load(context) }
    }
    val pinnedEntries = remember(entries, pinnedIds) {
        pinnedIds.mapNotNull { id -> entries.find { it.componentId == id } }
    }
    val frame by CarEnvironment.frame.collectAsState()

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                // Home and Apps are two different places now, not a toggle on one button - the
                // breadcrumb trail is how you retrace your steps, but reaching for either of
                // these two from three screens deep in a media app shouldn't mean reading it
                // first.
                NavItem(Icons.Filled.Home, navigator.current == CarScreen.Home) { navigator.home() }
                Spacer(Modifier.width(6.dp))
                NavItem(MediaIcons.Apps, navigator.current == CarScreen.Apps) { navigator.apps() }

                openMapComponent?.let { component ->
                    Spacer(Modifier.width(6.dp))
                    NavAppIcon(component, contentDescription = "Map", onClick = navigator::home)
                }
                pinnedEntries.forEach { entry ->
                    Spacer(Modifier.width(6.dp))
                    entry.componentId?.let { id ->
                        NavAppIcon(id, contentDescription = entry.label) { launchEntry(entry, navigator, frame) }
                    }
                }

                Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    NavNowPlaying(navigator, selected = navigator.current == CarScreen.Media)
                }

                // Weather no longer has its own chip - the temperature alone (no icon, no room
                // for one) sits where "Night"/"Day" used to, freeing a whole chip's width for the
                // Home button above.
                NavChip(navigator.current == CarScreen.Dashboard, { navigator.dashboard() }) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (battery.percent >= 0) {
                                Text(
                                    "${battery.percent}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (battery.charging) {
                                    ChargingGlyph(Modifier.size(13.dp))
                                }
                            }
                            weatherData?.let { w ->
                                Text(
                                    "  ·  ${w.temp}°",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                NavItem(
                    Icons.Filled.Notifications, navigator.current is CarScreen.Notifications, padding = 8.dp,
                ) { navigator.notifications() }
            }
        }
    }
}

/** One open or pinned app's icon on the nav bar - the same fixed size and shape either way. */
@Composable
private fun NavAppIcon(component: String, contentDescription: String?, onClick: () -> Unit) {
    val context = LocalContext.current
    val pkg = remember(component) { android.content.ComponentName.unflattenFromString(component)?.packageName }
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pkg) {
        value = pkg?.let {
            runCatching { context.packageManager.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon?.let { Image(it, contentDescription, Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))) }
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
private fun NavItem(icon: ImageVector, selected: Boolean, padding: androidx.compose.ui.unit.Dp = 12.dp, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(padding),
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

/**
 * The one persistent player, everywhere - not just a Home-screen extra. Art and title open the
 * full media screen; skip and play/pause work right here; the last button toggles the lyrics
 * panel beside the map on Home (see [CarHome]), the pill's own lyrics affordance wherever it sits.
 */
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
        FilledIconButton(
            onClick = navigator::toggleLyrics,
            modifier = Modifier.size(44.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = if (navigator.lyricsOpen) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (navigator.lyricsOpen) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            LyricsGlyph(Modifier.size(22.dp))
        }
    }
    }
}

/**
 * A card with two lines of text on it - read as "lyrics"/"captions" at a glance, which no icon in
 * the core Material set does (the closest, a bullet list, reads as a menu, not song text). Drawn
 * rather than a vector asset since the whole glyph is two shapes.
 */
@Composable
private fun LyricsGlyph(modifier: Modifier = Modifier) {
    val tint = androidx.compose.material3.LocalContentColor.current
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = size.minDimension * 0.11f
        drawRoundRect(
            color = tint,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.22f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        val lineStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke * 0.85f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val startX = size.width * 0.24f
        listOf(0.40f, 0.64f).forEach { fy ->
            val endX = size.width * (if (fy < 0.5f) 0.76f else 0.62f)
            drawLine(tint, androidx.compose.ui.geometry.Offset(startX, size.height * fy), androidx.compose.ui.geometry.Offset(endX, size.height * fy), strokeWidth = lineStyle.width, cap = lineStyle.cap)
        }
    }
}

/**
 * A filled lightning bolt, next to the battery percentage instead of the word "charging" - the
 * core Material icon set this project ships (no extended pack, to keep the app small) has no
 * bolt, so this is drawn the same way [LyricsGlyph] is.
 */
@Composable
private fun ChargingGlyph(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bolt = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.58f, 0f)
            lineTo(w * 0.12f, h * 0.58f)
            lineTo(w * 0.46f, h * 0.58f)
            lineTo(w * 0.42f, h)
            lineTo(w * 0.88f, h * 0.40f)
            lineTo(w * 0.54f, h * 0.40f)
            close()
        }
        drawPath(bolt, tint)
    }
}
