package app.seb3thehacker.gearslip.car

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

sealed interface CarScreen {
    data object Home : CarScreen
    data object Settings : CarScreen
    data object Apps : CarScreen

    /** Opened from the clock: the day's calendar and the weather, nothing that needs a keyboard. */
    data object Dashboard : CarScreen

    /** Opened from the weather chip or the dashboard: now, the next hours and the week. */
    data object Weather : CarScreen

    /** The current media app, full size: browse, lyrics and controls. */
    data object Media : CarScreen

    /** [replyTo] is a notification key to open straight into its reply pane. */
    data class Notifications(val replyTo: String? = null) : CarScreen
    data class App(val id: String) : CarScreen
}

/** One step on the breadcrumb trail: the screen it shows and the word for it in the trail. */
data class Crumb(val screen: CarScreen, val label: String)

/**
 * Where the car UI is, and how it got there. A car touchscreen has no hardware back button, so
 * every screen that isn't reached from [home] is pushed onto a trail (like a browser's history,
 * or a file manager's breadcrumb bar) rather than replacing the last one outright - that's what
 * lets the breadcrumb bar show "Home > Apps > Settings" and jump straight back to any of them,
 * and lets [back] undo exactly one step instead of always dumping you back at Home. An open app
 * can claim a back step first (a browser going back a page) through [backInterceptor].
 */
class CarNavigator(startId: String) {

    private val stack = mutableStateListOf(
        Crumb(
            if (CarApps.find(startId) != null) CarScreen.App(startId) else CarScreen.Home,
            "Home",
        ),
    )

    /** The whole trail, root first. Size 1 means "at Home" - the breadcrumb bar hides then. */
    val trail: List<Crumb> get() = stack

    val current: CarScreen get() = stack.last().screen

    var backInterceptor: (() -> Boolean)? = null

    /** Pushes a new step, unless it's the same screen already on top - tapping the button for
     * where you already are shouldn't grow the trail. */
    private fun push(screen: CarScreen, label: String) {
        if (stack.last().screen == screen) return
        stack.add(Crumb(screen, label))
    }

    /** The one way back to a clean slate: resets the whole trail to just Home. */
    fun home() {
        stack.clear()
        stack.add(Crumb(CarScreen.Home, "Home"))
    }

    /**
     * The nav bar's now-playing pill is the player everywhere; this is only whether the home
     * screen is additionally showing lyrics for it in the column beside the map. Toggled by the
     * pill's own lyrics button, so it means the same thing wherever the pill is visible, even
     * though only the home screen actually has the room to show it.
     */
    var lyricsOpen by mutableStateOf(false)
        private set

    fun toggleLyrics() { lyricsOpen = !lyricsOpen }

    fun media() { push(CarScreen.Media, "Media") }

    fun apps() { push(CarScreen.Apps, "Apps") }

    fun settings() { push(CarScreen.Settings, "Settings") }

    fun dashboard() { push(CarScreen.Dashboard, "Dashboard") }

    fun weather() { push(CarScreen.Weather, "Weather") }

    fun notifications(replyTo: String? = null) { push(CarScreen.Notifications(replyTo), "Notifications") }

    fun open(id: String) { push(CarScreen.App(id), CarApps.find(id)?.label ?: id) }

    /** Jumps straight to a step already on the trail, dropping everything after it - what
     * tapping a breadcrumb does. Index 0 (Home) is always on the trail, so this always succeeds. */
    fun jumpTo(index: Int) {
        while (stack.size > index + 1) stack.removeAt(stack.lastIndex)
    }

    /** One step back, same as tapping the crumb before the current one. */
    fun back() {
        if (backInterceptor?.invoke() == true) return
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

val LocalCarNavigator = staticCompositionLocalOf<CarNavigator> { error("no CarNavigator provided") }

/** "Is it dark outside", for apps that adapt to it. See [CarEnvironment]. */
val LocalDarkOutside = staticCompositionLocalOf { false }
