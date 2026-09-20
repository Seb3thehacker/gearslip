package app.seb3thehacker.gearslip.car

import android.content.ComponentName
import android.content.Context
import app.seb3thehacker.gearslip.audio.CarAudio
import app.seb3thehacker.gearslip.host.CarAppCatalog
import app.seb3thehacker.gearslip.host.CarAppConnection
import app.seb3thehacker.gearslip.host.TemplateApp
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.MediaApp
import app.seb3thehacker.gearslip.media.MediaCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The two long-lived things the car keeps running: the map app and the media app.
 *
 * They belong to the car session rather than to whichever screen is showing, so the home
 * screen, the Car apps list and the media screen all look at the same connection. Leaving a
 * screen must not drop the route being followed or the song being played.
 */
object CarServices {

    private var context: Context? = null

    val nav: CarAppConnection by lazy { CarAppConnection(requireNotNull(context)) }
    val media: CarMedia by lazy { CarMedia(requireNotNull(context)) }

    private val _mediaApp = MutableStateFlow<MediaApp?>(null)
    val mediaApp: StateFlow<MediaApp?> = _mediaApp.asStateFlow()

    /** Set when the driver stops the map by hand, so going Home doesn't immediately restart it. */
    private var navStopped = false
    private var autoplayPending = false

    fun init(appContext: Context) {
        context = appContext.applicationContext
        app.seb3thehacker.gearslip.notify.CarMotion.start(appContext)
    }

    // --- map -----------------------------------------------------------------------------

    fun connectNav(app: TemplateApp, frame: CarEnvironment.Frame) {
        navStopped = false
        CarSettings.setLastNav(app.component.flattenToString())
        nav.connect(app, frame.width, frame.height, frame.densityDpi)
    }

    fun stopNav() {
        navStopped = true
        nav.disconnect()
    }

    // --- media ---------------------------------------------------------------------------

    fun openMedia(app: MediaApp, autoplay: Boolean = false) {
        CarSettings.setLastMedia(app.component.flattenToString())
        val phase = media.phase.value
        val live = phase == CarMedia.Phase.READY || phase == CarMedia.Phase.CONNECTING
        if (_mediaApp.value?.component == app.component && live) return

        CarAudio.release()
        _mediaApp.value = app
        autoplayPending = autoplay
        media.connect(app)
        if (CarSettings.pipeAudio.value) CarAudio.request(app.uid)
    }

    /** Called once the media app is connected: starts playback if that was asked for. */
    fun autoplayIfDue() {
        if (!autoplayPending) return
        autoplayPending = false
        if (!media.now.value.playing) media.togglePlay()
    }

    // --- start and stop ------------------------------------------------------------------

    /** Brings back whatever was in use last time. Safe to call repeatedly. */
    suspend fun autostart(frame: CarEnvironment.Frame) {
        val ctx = context ?: return

        if (!navStopped && nav.status.value.phase == CarAppConnection.Phase.IDLE) {
            val last = CarSettings.lastNav.value
            val app = last?.let { id ->
                withContext(Dispatchers.IO) { CarAppCatalog.installed(ctx) }
                    .firstOrNull { it.component == ComponentName.unflattenFromString(id) }
            }
            if (app != null) connectNav(app, frame)
        }

        if (_mediaApp.value == null) {
            val last = CarSettings.lastMedia.value
            val app = last?.let { id ->
                withContext(Dispatchers.IO) { MediaCatalog.installed(ctx) }
                    .firstOrNull { it.component == ComponentName.unflattenFromString(id) }
            }
            if (app != null) openMedia(app, autoplay = CarSettings.autoplay.value)
        }
    }

    /** The car went away: nothing should keep running for a screen that no longer exists. */
    fun shutdown() {
        context?.let { app.seb3thehacker.gearslip.notify.CarMotion.stop(it) }
        runCatching { nav.disconnect() }
        runCatching { media.disconnect() }
        CarAudio.release()
        _mediaApp.value = null
        navStopped = false
    }
}
