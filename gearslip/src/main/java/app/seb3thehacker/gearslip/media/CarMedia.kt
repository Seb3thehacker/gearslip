package app.seb3thehacker.gearslip.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.notify.GearslipNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One row of an app's browse tree. */
class MediaEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconUri: Uri?,
    val iconBitmap: Bitmap?,
    val browsable: Boolean,
    val playable: Boolean,
)

class CustomAction(val id: String, val name: String, val extras: Bundle?)

/** What is playing, as the car's now-playing panel needs it. */
class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val art: Bitmap? = null,
    val artUri: Uri? = null,
    val state: Int = PlaybackState.STATE_NONE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val updatedAt: Long = 0,
    val actions: Long = 0,
    val custom: List<CustomAction> = emptyList(),
    val error: String? = null,
) {
    val playing get() = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    val hasTrack get() = title.isNotEmpty() || state != PlaybackState.STATE_NONE

    /**
     * Something is actually playing or paused mid-track. A stopped, errored or empty session, or one
     * that has not named a track yet, must not keep the player on screen.
     */
    val isActive get() = title.isNotEmpty() && when (state) {
        PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> false
        else -> true
    }

    fun canDo(action: Long) = actions and action != 0L

    /** The position now, advanced from the last report the way the platform's own UIs do. */
    fun currentPosition(): Long {
        if (!playing || state != PlaybackState.STATE_PLAYING) return positionMs
        val elapsed = SystemClock.elapsedRealtime() - updatedAt
        val moved = positionMs + (elapsed * speed).toLong()
        return if (durationMs > 0) moved.coerceAtMost(durationMs) else moved
    }
}

class BrowseState(
    /** Titles of the folders entered so far; empty at the app's root. */
    val trail: List<String> = emptyList(),
    val entries: List<MediaEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** The app connected but shares no library with us, so there is nothing to browse - only to control. */
    val unavailable: Boolean = false,
)

/**
 * Talks to one media app the way Android Auto does: connect to its MediaBrowserService, browse
 * its tree, and drive playback through the session it publishes. The app decides what its
 * tree contains and what the buttons do; this only presents it.
 */
class CarMedia(private val context: Context) {

    enum class Phase { IDLE, CONNECTING, READY, REJECTED }

    /** Why a connection was [Phase.REJECTED], so the screen can say something useful. */
    enum class Rejection { REFUSED, NEEDS_NOTIFICATION_ACCESS }

    private val _rejection = MutableStateFlow(Rejection.REFUSED)
    val rejection: StateFlow<Rejection> = _rejection.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var connecting: MediaApp? = null

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now.asStateFlow()

    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var subscribed: String? = null
    private var rootId: String? = null
    private val path = ArrayDeque<Pair<String, String>>() // id to title

    fun connect(app: MediaApp) {
        disconnect()
        connecting = app
        _phase.value = Phase.CONNECTING
        _browse.value = BrowseState()
        val mb = MediaBrowser(context, app.component, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() = this@CarMedia.onConnected(app)
            override fun onConnectionFailed() {
                GearslipLog.w("media: ${app.label} refused the connection to its library")
                attachToSession(app)
            }
            override fun onConnectionSuspended() {
                GearslipLog.w("media: ${app.label} went away")
                _phase.value = Phase.REJECTED
            }
        }, null)
        browser = mb
        runCatching { mb.connect() }.onFailure {
            GearslipLog.e("media: connect threw", it)
            _phase.value = Phase.REJECTED
        }
    }

    /**
     * Some apps only let Google's own host browse them. Their playback session is still public, so
     * playback can be driven from it: what is playing, play and pause, skip, seek. Only the library
     * is out of reach. Reading another app's session needs Notification access, which the driver
     * grants once on the phone.
     */
    private fun attachToSession(app: MediaApp, attempt: Int = 0) {
        if (connecting != app) return
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, GearslipNotificationListener::class.java)
        val session = try {
            manager.getActiveSessions(listener).firstOrNull { it.packageName == app.component.packageName }
        } catch (e: SecurityException) {
            GearslipLog.w("media: cannot read ${app.label}'s session without notification access")
            _rejection.value = Rejection.NEEDS_NOTIFICATION_ACCESS
            _phase.value = Phase.REJECTED
            return
        }
        if (session == null) {
            // The app only publishes its session once its service has started; give it a moment.
            if (attempt < SESSION_RETRIES) {
                main.postDelayed({ attachToSession(app, attempt + 1) }, SESSION_RETRY_MS)
            } else {
                GearslipLog.w("media: ${app.label} has no active session to control")
                _rejection.value = Rejection.REFUSED
                _phase.value = Phase.REJECTED
            }
            return
        }
        GearslipLog.i("media: controlling ${app.label} through its session; its library is not shared")
        controller = session.also {
            it.registerCallback(callback)
            update(it.metadata, it.playbackState)
        }
        _browse.value = BrowseState(loading = false, unavailable = true)
        _phase.value = Phase.READY
    }

    private fun onConnected(app: MediaApp) {
        val mb = browser ?: return
        GearslipLog.i("media: connected to ${app.label}, root=${mb.root}")
        controller = MediaController(context, mb.sessionToken).also {
            it.registerCallback(callback)
            update(it.metadata, it.playbackState)
        }
        rootId = mb.root
        path.clear()
        _phase.value = Phase.READY
        open(mb.root)
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = update(metadata, controller?.playbackState)
        override fun onPlaybackStateChanged(state: PlaybackState?) = update(controller?.metadata, state)
    }

    private fun update(metadata: MediaMetadata?, state: PlaybackState?) {
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { metadata?.getString(it)?.takeIf(String::isNotEmpty) }.orEmpty()
        fun art(vararg keys: String) = keys.firstNotNullOfOrNull { metadata?.getBitmap(it) }
        fun uri(vararg keys: String) = keys.firstNotNullOfOrNull { metadata?.getString(it)?.takeIf(String::isNotEmpty) }?.let(Uri::parse)
        _now.value = NowPlaying(
            title = text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE),
            artist = text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST),
            album = text(MediaMetadata.METADATA_KEY_ALBUM),
            art = art(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            artUri = uri(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            state = state?.state ?: PlaybackState.STATE_NONE,
            positionMs = state?.position ?: 0,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            speed = state?.playbackSpeed ?: 1f,
            updatedAt = state?.lastPositionUpdateTime ?: 0,
            actions = state?.actions ?: 0,
            custom = state?.customActions.orEmpty().map { CustomAction(it.action, it.name.toString(), it.extras) },
            error = state?.errorMessage?.toString(),
        )
    }

    // --- browsing ------------------------------------------------------------------------

    private fun open(parentId: String) {
        val mb = browser ?: return
        subscribed?.let { runCatching { mb.unsubscribe(it) } }
        subscribed = parentId
        _browse.update { BrowseState(trail = path.map { it.second }, loading = true) }
        mb.subscribe(parentId, object : MediaBrowser.SubscriptionCallback() {
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                if (parentId != subscribed) return
                _browse.value = BrowseState(
                    trail = path.map { it.second },
                    entries = children.map(::entryOf),
                    loading = false,
                    // An empty top level is an app keeping its library to itself, not an empty one.
                    unavailable = path.isEmpty() && children.isEmpty(),
                )
            }
            override fun onError(parentId: String) {
                if (parentId != subscribed) return
                GearslipLog.w("media: could not load $parentId")
                _browse.value = BrowseState(trail = path.map { it.second }, loading = false, failed = true)
            }
        })
    }

    private fun entryOf(item: MediaBrowser.MediaItem): MediaEntry {
        val d = item.description
        return MediaEntry(
            id = item.mediaId.orEmpty(),
            title = d.title?.toString().orEmpty(),
            subtitle = d.subtitle?.toString().orEmpty(),
            iconUri = d.iconUri,
            iconBitmap = d.iconBitmap,
            browsable = item.isBrowsable,
            playable = item.isPlayable,
        )
    }

    /** Folders open; tracks play. Some entries are both (an album you can browse or play whole). */
    fun select(entry: MediaEntry) {
        if (entry.browsable) {
            path.addLast(entry.id to entry.title)
            open(entry.id)
        } else if (entry.playable) {
            controller?.transportControls?.playFromMediaId(entry.id, null)
        }
    }

    fun playAll(entry: MediaEntry) {
        controller?.transportControls?.playFromMediaId(entry.id, null)
    }

    val canGoUp get() = path.isNotEmpty()

    fun up() {
        if (path.isEmpty()) return
        path.removeLast()
        open(path.lastOrNull()?.first ?: rootId ?: return)
    }

    // --- transport -----------------------------------------------------------------------

    fun togglePlay() {
        val controls = controller?.transportControls ?: return
        if (_now.value.playing) controls.pause() else controls.play()
    }
    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
    fun seek(ms: Long) = controller?.transportControls?.seekTo(ms)
    fun custom(action: CustomAction) = controller?.transportControls?.sendCustomAction(action.id, action.extras)

    fun disconnect() {
        connecting = null
        main.removeCallbacksAndMessages(null)
        runCatching { controller?.unregisterCallback(callback) }
        runCatching { subscribed?.let { browser?.unsubscribe(it) } }
        runCatching { browser?.disconnect() }
        controller = null
        browser = null
        subscribed = null
        _phase.value = Phase.IDLE
        _now.value = NowPlaying()
    }

    private companion object {
        const val SESSION_RETRIES = 6
        const val SESSION_RETRY_MS = 500L
    }
}
