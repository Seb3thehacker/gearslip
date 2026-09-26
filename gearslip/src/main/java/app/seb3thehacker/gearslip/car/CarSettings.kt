package app.seb3thehacker.gearslip.car

import android.content.Context
import app.seb3thehacker.gearslip.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class NightMode { AUTO, DAY, NIGHT }

/** The car UI's own colours. Separate from [NightMode], which is about the light outside (the map). */
enum class AppTheme { PHONE, LIGHT, DARK }

/**
 * Settings changed from the car screen itself. Held as flows so the car UI reacts the moment a
 * button is tapped, and persisted through [AppSettings] so they survive the next connection.
 */
object CarSettings {

    private const val KEY_SCALE = "car_scale"
    private const val KEY_OPEN = "car_open_on_connect"
    private const val KEY_NIGHT = "car_night_mode"
    private const val KEY_PIPE_AUDIO = "car_pipe_audio"
    private const val KEY_LAST_NAV = "car_last_nav"
    private const val KEY_LAST_MEDIA = "car_last_media"
    private const val KEY_AUTOPLAY = "car_autoplay"
    private const val KEY_THEME = "car_app_theme"
    private const val KEY_PINNED = "car_pinned_apps"

    /** How many shortcuts the nav bar makes room for; a driver reaching for one needs it to
     * still be on the bar, not off the edge of a long list. */
    const val MAX_PINNED = 3

    /** Id of the app to open on connect; [HOME] opens the launcher. */
    const val HOME = "home"

    private lateinit var app: Context

    private val scaleFlow = MutableStateFlow(1f)
    private val openFlow = MutableStateFlow(HOME)
    private val nightFlow = MutableStateFlow(NightMode.AUTO)
    private val pipeAudioFlow = MutableStateFlow(true)
    private val lastNavFlow = MutableStateFlow<String?>(null)
    private val lastMediaFlow = MutableStateFlow<String?>(null)
    private val autoplayFlow = MutableStateFlow(false)
    private val themeFlow = MutableStateFlow(AppTheme.PHONE)
    val appTheme: StateFlow<AppTheme> = themeFlow
    private val pinnedFlow = MutableStateFlow<Set<String>>(emptySet())

    /** Flattened component names of the apps long-pressed onto the nav bar as shortcuts. */
    val pinnedApps: StateFlow<Set<String>> = pinnedFlow

    /** Flattened component names of the map and media apps used last, brought back on connect. */
    val lastNav: StateFlow<String?> = lastNavFlow
    val lastMedia: StateFlow<String?> = lastMediaFlow

    /** Whether the media app starts playing by itself when the car connects. */
    val autoplay: StateFlow<Boolean> = autoplayFlow

    val scale: StateFlow<Float> = scaleFlow
    val openOnConnect: StateFlow<String> = openFlow
    val nightMode: StateFlow<NightMode> = nightFlow

    /**
     * Whether a media app's sound is captured and sent over the car link. On by default, since
     * sound in the car is the point of the exercise; the platform allows the capture only behind a
     * consent dialog, which [app.seb3thehacker.gearslip.audio.CarAudio] raises once the car has
     * somewhere to play it. Turned off, the app plays through the phone's normal output instead.
     */
    val pipeAudio: StateFlow<Boolean> = pipeAudioFlow

    fun init(context: Context) {
        app = context.applicationContext
        scaleFlow.value = AppSettings.getString(app, KEY_SCALE, "1.0").toFloatOrNull() ?: 1f
        openFlow.value = AppSettings.getString(app, KEY_OPEN, HOME)
        lastNavFlow.value = AppSettings.getString(app, KEY_LAST_NAV, "").ifEmpty { null }
        lastMediaFlow.value = AppSettings.getString(app, KEY_LAST_MEDIA, "").ifEmpty { null }
        themeFlow.value = runCatching {
            AppTheme.valueOf(AppSettings.getString(app, KEY_THEME, AppTheme.PHONE.name))
        }.getOrDefault(AppTheme.PHONE)
        autoplayFlow.value = AppSettings.getString(app, KEY_AUTOPLAY, "false") == "true"
        pipeAudioFlow.value = AppSettings.getString(app, KEY_PIPE_AUDIO, "true") == "true"
        nightFlow.value = runCatching {
            NightMode.valueOf(AppSettings.getString(app, KEY_NIGHT, NightMode.AUTO.name))
        }.getOrDefault(NightMode.AUTO)
        pinnedFlow.value = AppSettings.getString(app, KEY_PINNED, "")
            .split(",").filter { it.isNotEmpty() }.toSet()
    }

    fun setScale(value: Float) {
        scaleFlow.value = value
        AppSettings.putString(app, KEY_SCALE, value.toString())
    }

    fun setOpenOnConnect(id: String) {
        openFlow.value = id
        AppSettings.putString(app, KEY_OPEN, id)
    }

    fun setLastNav(component: String) {
        lastNavFlow.value = component
        AppSettings.putString(app, KEY_LAST_NAV, component)
    }

    fun setLastMedia(component: String) {
        lastMediaFlow.value = component
        AppSettings.putString(app, KEY_LAST_MEDIA, component)
    }

    fun setAppTheme(theme: AppTheme) {
        themeFlow.value = theme
        AppSettings.putString(app, KEY_THEME, theme.name)
    }

    fun setAutoplay(on: Boolean) {
        autoplayFlow.value = on
        AppSettings.putString(app, KEY_AUTOPLAY, on.toString())
    }

    fun setPipeAudio(on: Boolean) {
        pipeAudioFlow.value = on
        AppSettings.putString(app, KEY_PIPE_AUDIO, on.toString())
    }

    fun setNightMode(mode: NightMode) {
        nightFlow.value = mode
        AppSettings.putString(app, KEY_NIGHT, mode.name)
        CarEnvironment.refresh()
    }

    /** Long-pressed onto or off the nav bar. Silently does nothing past [MAX_PINNED] rather than
     * bumping the oldest pin - a driver who filled the bar on purpose shouldn't lose one by accident. */
    fun togglePin(component: String) {
        val current = pinnedFlow.value
        val next = when {
            component in current -> current - component
            current.size >= MAX_PINNED -> return
            else -> current + component
        }
        pinnedFlow.value = next
        AppSettings.putString(app, KEY_PINNED, next.joinToString(","))
    }
}
