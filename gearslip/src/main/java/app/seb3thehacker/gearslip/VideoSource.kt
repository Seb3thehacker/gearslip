package app.seb3thehacker.gearslip

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.media.Image
import android.view.Surface

/**
 * Encodes a generated test card to H.264 Baseline and hands the bitstream to a callback.
 *
 * Feeds the encoder YUV420 buffers via getInputImage() rather than a Surface: a MediaCodec
 * input Surface requires an EGL/OpenGL context to render into (Surface.lockCanvas is not
 * supported on it), and for a static test card that is a lot of machinery for no benefit.
 * Byte-buffer input keeps the spike dependency-free and fully deterministic. Phase C swaps
 * this for a real Surface + GL path when the source becomes a live WebView.
 *
 * The head unit negotiated MEDIA_CODEC_VIDEO_H264_BP, so the profile is pinned to Baseline.
 */
class VideoSource(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val mode: Mode = Mode.TEST_CARD,
    private val onCodecConfig: (ByteArray) -> Unit,
    private val onFrame: (data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) -> Unit,
) {
    private val log = GearslipLog.tagged("VIDEO")

    /**
     * TEST_CARD feeds generated YUV buffers; SURFACE exposes a MediaCodec input Surface for
     * something else to render into (Phase C drives it from a VirtualDisplay + Presentation,
     * which is how Android screen mirroring works and avoids hand-written EGL entirely).
     */
    enum class Mode { TEST_CARD, SURFACE }

    /** Non-null in SURFACE mode once [start] has run. */
    var inputSurface: Surface? = null
        private set

    private var codec: MediaCodec? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private var frameIndex = 0L

    fun start() {
        val bitRate = (width.toLong() * height * frameRate * BITS_PER_PIXEL).toInt().coerceIn(MIN_BIT_RATE, MAX_BIT_RATE)
        fun format(tuned: Boolean) = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                if (mode == Mode.SURFACE) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                } else {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                },
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // Drops already ask for a fresh keyframe, so a long interval costs nothing in recovery
            // and spares the link a big frame every second.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            if (tuned) {
                // Hints only: a car screen wants each frame now, not the best frame a moment later.
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }

        var encoder = MediaCodec.createEncoderByType(MIME)
        try {
            encoder.configure(format(tuned = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders refuse a hint they don't know. The plain format is what always worked.
            log.w("encoder refused the tuned format (${e.message}) - using the plain one")
            encoder.release()
            encoder = MediaCodec.createEncoderByType(MIME)
            encoder.configure(format(tuned = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        if (mode == Mode.SURFACE) inputSurface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
        running = true

        log.i("encoder started: ${width}x$height @ ${frameRate}fps, ${bitRate / 1000} kbps, H.264 Baseline, mode=$mode")

        worker = Thread {
            runCatching { pump(encoder) }
                .onFailure { if (running) log.e("encoder loop failed", it) }
        }.apply { name = "gearslip-encoder"; start() }
    }

    /**
     * Forces the next encoded frame to be a full keyframe (IDR) rather than a P-frame.
     *
     * Needed whenever a frame gets dropped between us and the head unit: every frame after
     * the drop is a P-frame that assumes the decoder still has the reference picture we
     * never delivered. Without a resync, the decoder free-runs on a corrupted reference
     * forever - visually, exactly the smeared/ghosted look of a partial e-ink refresh,
     * because nothing ever forces a clean full-frame redraw.
     */
    fun requestSyncFrame() {
        runCatching {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { log.w("requestSyncFrame failed: ${it.message}") }
    }

    fun stop() {
        running = false
        worker?.join(1_000)
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private fun pump(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val frameIntervalUs = 1_000_000L / frameRate

        while (running) {
            // SURFACE mode has no input buffers to fill - frames arrive from the display.
            val inIndex = if (mode == Mode.SURFACE) -1 else encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val image = encoder.getInputImage(inIndex)
                if (image != null) {
                    drawTestCard(image, frameIndex)
                    encoder.queueInputBuffer(
                        inIndex, 0, width * height * 3 / 2, frameIndex * frameIntervalUs, 0,
                    )
                } else {
                    encoder.queueInputBuffer(inIndex, 0, 0, frameIndex * frameIntervalUs, 0)
                }
                frameIndex++
            }

            // A surface-fed encoder has nothing to do between frames, so block until one is ready
            // instead of sleeping a fixed time: less delay on screen, fewer wakeups for the CPU.
            val wait = if (mode == Mode.SURFACE) SURFACE_TIMEOUT_US else TIMEOUT_US
            var outIndex = encoder.dequeueOutputBuffer(info, wait)
            while (outIndex >= 0) {
                val buffer = encoder.getOutputBuffer(outIndex)
                if (buffer != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        onCodecConfig(data)
                    } else {
                        onFrame(
                            data,
                            info.presentationTimeUs,
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                        )
                    }
                }
                encoder.releaseOutputBuffer(outIndex, false)
                outIndex = encoder.dequeueOutputBuffer(info, 0)
            }

            if (mode != Mode.SURFACE) Thread.sleep(maxOf(1L, frameIntervalUs / 2000))
        }
    }

    /**
     * Colour bars with a bar that advances each second, so the car screen shows something
     * unmistakably ours and obviously live rather than a frozen or stale frame.
     */
    private fun drawTestCard(image: Image, frame: Long) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val bars = BAR_COLOURS.size
        val barHeight = maxOf(1, height / bars)
        val marker = ((frame / frameRate) % bars).toInt()

        val yBuf = yPlane.buffer
        val yStride = yPlane.rowStride
        for (row in 0 until height) {
            val bar = minOf(bars - 1, row / barHeight)
            val (luma, _, _) = BAR_COLOURS[bar]
            val value = if (bar == marker) 235 else luma
            val base = row * yStride
            for (col in 0 until width) {
                yBuf.put(base + col, value.toByte())
            }
        }

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (row in 0 until height / 2) {
            val bar = minOf(bars - 1, (row * 2) / barHeight)
            val (_, cb, cr) = BAR_COLOURS[bar]
            for (col in 0 until width / 2) {
                uBuf.put(row * uPlane.rowStride + col * uPlane.pixelStride, cb.toByte())
                vBuf.put(row * vPlane.rowStride + col * vPlane.pixelStride, cr.toByte())
            }
        }
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val TIMEOUT_US = 10_000L
        const val SURFACE_TIMEOUT_US = 100_000L

        /** Enough for sharp text and map lines; 800x480 at 30fps comes out at 4 Mbps. */
        const val BITS_PER_PIXEL = 0.3
        const val MIN_BIT_RATE = 4_000_000
        const val MAX_BIT_RATE = 12_000_000
        const val I_FRAME_INTERVAL_S = 5

        /** (Y, Cb, Cr) - white, yellow, cyan, green, magenta, red, blue. */
        val BAR_COLOURS = listOf(
            Triple(180, 128, 128),
            Triple(162, 44, 142),
            Triple(131, 156, 44),
            Triple(112, 72, 58),
            Triple(84, 184, 198),
            Triple(65, 100, 212),
            Triple(35, 212, 114),
        )
    }
}
