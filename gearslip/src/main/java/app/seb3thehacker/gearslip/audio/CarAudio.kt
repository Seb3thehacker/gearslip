package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Joins the two ends of the audio path: [AudioCaptureService] on the phone, which hears what a
 * media app plays, and the car's audio channel, which is where it has to end up.
 *
 * They come and go independently. The phone side needs on-device consent and can be running on
 * the bench with no car attached; the car side exists only while a head unit is connected and
 * offers a media audio sink. Whichever pair is present, samples flow.
 */
object CarAudio {

    /** What the head unit's media sink accepts, and how to hand it audio. */
    interface Link {
        val sampleRate: Int
        val channels: Int
        fun start()
        fun stop()
        fun write(pcm: ByteArray, size: Int)
    }

    enum class Capture { OFF, ASKING, ON, DENIED }

    private val _capture = MutableStateFlow(Capture.OFF)
    val capture: StateFlow<Capture> = _capture.asStateFlow()

    /** "-12 dB" style summary of what was last heard, for the media screen. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Set by the activity: shows the permission and consent dialogs, which only it can. */
    var onConsentRequested: (() -> Unit)? = null

    /** The uid being captured (or waited on). */
    @Volatile var uid: Int = -1
        private set

    @Volatile private var link: Link? = null

    /** Rate and channel count the capture should use: the car's, or a sensible default. */
    val sampleRate get() = link?.sampleRate ?: 48_000
    val channels get() = link?.channels ?: 2

    fun attach(link: Link) {
        this.link = link
        if (_capture.value == Capture.ON) link.start()
    }

    fun detach(link: Link) {
        if (this.link === link) this.link = null
    }

    /** A media app was opened: start hearing it. */
    fun request(uid: Int) {
        if (this.uid == uid && _capture.value != Capture.OFF && _capture.value != Capture.DENIED) return
        if (this.uid != uid) AudioCaptureService.stop()
        this.uid = uid
        _capture.value = Capture.ASKING
        val ask = onConsentRequested
        if (ask == null) {
            GearslipLog.w("audio: nothing on the phone can ask for capture consent")
            _capture.value = Capture.DENIED
        } else {
            ask()
        }
    }

    fun release() {
        uid = -1
        AudioCaptureService.stop()
        _capture.value = Capture.OFF
    }

    fun denied() {
        _capture.value = Capture.DENIED
    }

    internal fun captureStarted() {
        _capture.value = Capture.ON
        link?.start()
    }

    internal fun captureStopped() {
        link?.stop()
        _level.value = 0f
        if (_capture.value == Capture.ON) _capture.value = Capture.OFF
    }

    internal fun deliver(pcm: ByteArray, size: Int, peak: Float) {
        _level.value = peak
        link?.write(pcm, size)
    }
}
