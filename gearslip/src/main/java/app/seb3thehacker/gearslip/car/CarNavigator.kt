package app.seb3thehacker.gearslip.car

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

sealed interface CarScreen {
    data object Home : CarScreen
    data object Settings : CarScreen
    data object Apps : CarScreen

    /** Opened from the clock: the day's calendar and the weather, nothing that needs a keyboard. */
    data object Dashboard : CarScreen

    /** The current media app, full size: browse, lyrics and controls. */
    data object Media : CarScreen

    /** [replyTo] is a notification key to open straight into its reply pane. */
    data class Notifications(val replyTo: String? = null) : CarScreen
    data class App(val id: String) : CarScreen
}

/**
 * Where the car UI is. A car touchscreen has no hardware back button, so [back] is driven by
 * the on-screen one; an open app can claim it first (a browser going back a page) through
 * [backInterceptor].
 */
class CarNavigator(startId: String) {

    var current: CarScreen by mutableStateOf(
        if (CarApps.find(startId) != null) CarScreen.App(startId) else CarScreen.Home,
    )
        private set

    var backInterceptor: (() -> Boolean)? = null

    fun home() { current = CarScreen.Home }

    fun media() { current = CarScreen.Media }

    fun apps() { current = CarScreen.Apps }

    fun settings() { current = CarScreen.Settings }

    fun dashboard() { current = CarScreen.Dashboard }

    fun notifications(replyTo: String? = null) { current = CarScreen.Notifications(replyTo) }

    fun open(id: String) { current = CarScreen.App(id) }

    fun back() {
        if (backInterceptor?.invoke() == true) return
        current = CarScreen.Home
    }
}

val LocalCarNavigator = staticCompositionLocalOf<CarNavigator> { error("no CarNavigator provided") }

/** "Is it dark outside", for apps that adapt to it. See [CarEnvironment]. */
val LocalDarkOutside = staticCompositionLocalOf { false }
