package app.seb3thehacker.gearslip.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.seb3thehacker.gearslip.AppSettings
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
        Screen.HELP -> HelpScreen(onBack = { screen = Screen.HOME }, onRequestCallScreening = onRequestCallScreening)
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

    CompatWarning()
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
