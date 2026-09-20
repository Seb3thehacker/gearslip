package app.seb3thehacker.gearslip.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

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
}
