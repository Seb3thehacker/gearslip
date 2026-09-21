package app.seb3thehacker.gearslip.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.seb3thehacker.gearslip.AppSettings

private enum class Screen { HOME, LOGS, SETTINGS, CAR_PREVIEW }

/** Three screens and a back stack of depth one: no navigation library needed. */
@Composable
fun GearslipApp(onDisconnect: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onOpenLogs = { screen = Screen.LOGS },
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenCarPreview = { screen = Screen.CAR_PREVIEW },
            onDisconnect = onDisconnect,
        )
        Screen.LOGS -> LogsScreen(onBack = { screen = Screen.HOME })
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
        Screen.CAR_PREVIEW -> CarPreviewScreen(onBack = { screen = Screen.HOME })
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
                    "Gearslip may not connect on cars or head units newer than about 2020. " +
                        "Older systems are more likely to work; newer firmware often rejects the " +
                        "connection outright.",
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
