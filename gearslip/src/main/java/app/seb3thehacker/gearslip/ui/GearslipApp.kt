package app.seb3thehacker.gearslip.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.CertProvider

private enum class Screen { HOME, LOGS, SETTINGS, CAR_PREVIEW, CERT_SETUP, SETUP_GUIDE, HELP }

/** Three screens and a back stack of depth one: no navigation library needed. */
@Composable
fun GearslipApp(onDisconnect: () -> Unit, onRequestCallScreening: () -> Unit) {
    val context = LocalContext.current
    val needsCertSetup = remember {
        !AppSettings.hasSkippedCertSetup(context) && !CertProvider.hasAnyCert(context)
    }
    val needsSetupGuide = remember { !AppSettings.hasSeenPermissionsSetup(context) }
    var screen by rememberSaveable {
        mutableStateOf(
            when {
                needsCertSetup -> Screen.CERT_SETUP
                needsSetupGuide -> Screen.SETUP_GUIDE
                else -> Screen.HOME
            },
        )
    }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .consumeWindowInsets(WindowInsets.statusBars),
    ) {
        if (BuildConfig.DEBUG) DevBuildBanner()

        Column(Modifier.weight(1f)) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    onOpenLogs = { screen = Screen.LOGS },
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onOpenCarPreview = { screen = Screen.CAR_PREVIEW },
                    onOpenHelp = { screen = Screen.HELP },
                    onDisconnect = onDisconnect,
                )
                Screen.LOGS -> LogsScreen(onBack = { screen = Screen.HOME })
                Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
                Screen.CAR_PREVIEW -> CarPreviewScreen(onBack = { screen = Screen.HOME })
                Screen.HELP -> HelpScreen(
                    onBack = { screen = Screen.HOME },
                    onRequestCallScreening = onRequestCallScreening,
                    onReplayTutorial = { screen = Screen.SETUP_GUIDE },
                )
                Screen.CERT_SETUP -> CertSetupScreen(
                    onDone = {
                        screen = if (AppSettings.hasSeenPermissionsSetup(context)) Screen.HOME else Screen.SETUP_GUIDE
                    },
                )
                Screen.SETUP_GUIDE -> SetupGuideScreen(
                    onRequestCallScreening = onRequestCallScreening,
                    onDone = { screen = Screen.HOME },
                )
            }
        }
    }

    CompatWarning()
}

/**
 * Debug builds only ([BuildConfig.DEBUG]); a release build never composes this. Sits above every
 * phone screen, below the status bar - not the car screen, which is a separate render target
 * ([app.seb3thehacker.gearslip.car.CarUi] carries its own corner badge) - so a debug install is
 * never mistaken for a release one.
 */
@Composable
private fun DevBuildBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth().height(28.dp),
        color = Color(0xFFF5C518),
        contentColor = Color.Black,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "DEV BUILD",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Shown once, on first run: the certificate is known to fail on head units newer than ~2020. */
@Composable
private fun CompatWarning() {
    val context = LocalContext.current
    var visible by rememberSaveable { mutableStateOf(!AppSettings.hasSeenCompatWarning(context)) }

    if (visible) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Before you plug in") },
            text = {
                Text(
                    "Gearslip works with cars built before 2020. " +
                        "Newer firmware often blocks the connection.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppSettings.setSeenCompatWarning(context)
                    visible = false
                }) { Text("Got it") }
            },
        )
    }
}
