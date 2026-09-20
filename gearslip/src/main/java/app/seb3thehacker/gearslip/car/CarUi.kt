package app.seb3thehacker.gearslip.car

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    remember(appContext) { CarServices.init(appContext) }
    DisposableEffect(Unit) { onDispose { CarServices.shutdown() } }
    // The map follows the light outside, whatever the app's own theme is set to.
    LaunchedEffect(darkOutside) { CarServices.nav.pushConfiguration() }

    CompositionLocalProvider(
        LocalDensity provides Density(base.density * scale, base.fontScale),
        LocalCarNavigator provides navigator,
        LocalDarkOutside provides darkOutside,
    ) {
        MaterialTheme(colorScheme = colors) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

/** Back, Home and Apps on the left; clock, phone battery and the darkness signal on the right. */
@Composable
private fun CarNavBar(navigator: CarNavigator) {
    val battery by CarEnvironment.battery.collectAsState()
    val darkOutside by CarEnvironment.darkOutside.collectAsState()
    val now by rememberNow()

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(Icons.AutoMirrored.Filled.ArrowBack, "Back", selected = false) { navigator.back() }
            Spacer(Modifier.width(8.dp))
            NavItem(Icons.Filled.Home, "Home", navigator.current == CarScreen.Home) { navigator.home() }
            Spacer(Modifier.width(8.dp))
            NavItem(Icons.Filled.Menu, "Apps", navigator.current == CarScreen.Apps) { navigator.apps() }

            Spacer(Modifier.weight(1f))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val phone = if (battery.percent >= 0) "${battery.percent}%${if (battery.charging) " charging" else ""}" else ""
                val light = if (darkOutside) "Night" else "Day"
                Text(
                    listOf(phone, light).filter { it.isNotEmpty() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            NavItem(
                Icons.Filled.Notifications, null, navigator.current is CarScreen.Notifications,
            ) { navigator.notifications() }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
        }
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge)
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
