package app.seb3thehacker.gearslip

/**
 * Pulls the video service out of a ServiceDiscoveryResponse.
 *
 * Shape from aasdk (`service/Service.proto`, `service/media/sink/MediaSinkService.proto`,
 * `.../message/VideoConfiguration.proto`):
 *
 *   ServiceDiscoveryResponse { repeated Service channels = 1; ... }
 *   Service { required int32 id = 1; optional MediaSinkService media_sink_service = 3; ... }
 *   MediaSinkService { optional MediaCodecType available_type = 1;
 *                      repeated VideoConfiguration video_configs = 4; ... }
 *   VideoConfiguration { codec_resolution = 1; frame_rate = 2; density = 5; ... }
 *
 * The channel id used in the frame header is this service's `id` - real Android Auto assigns
 * channel ids dynamically (aasdk's static ChannelId enum is an approximation; see the TODO
 * in include/aasdk/Messenger/ChannelId.hpp).
 */
object ServiceDiscovery {

    /**
     * [widthMargin]/[heightMargin] are the head unit's own numbers (VideoConfiguration fields 3
     * and 4): pixels of the frame it does not treat as usable UI. Logged, and available to a
     * vehicle profile, but not applied automatically - what they mean varies by unit.
     */
    class VideoConfig(
        val resolution: Int,
        val frameRate: Int,
        val density: Int,
        val widthMargin: Int = 0,
        val heightMargin: Int = 0,
    ) {
        val resolutionName: String get() = RESOLUTIONS[resolution] ?: "unknown($resolution)"
        val frameRateName: String get() = if (frameRate == 1) "60fps" else "30fps"

        /** Pixel dimensions for this resolution enum, or null if we don't know it. */
        fun pixelSize(): Pair<Int, Int>? = PIXEL_SIZES[resolution]

        fun pixelCount(): Int = pixelSize()?.let { it.first * it.second } ?: Int.MAX_VALUE

        override fun toString() =
            "$resolutionName @ $frameRateName, density=$density, margin=${widthMargin}x$heightMargin"
    }

    class VideoService(val serviceId: Int, val codecType: Int, val configs: List<VideoConfig>) {
        val codecName: String get() = CODECS[codecType] ?: "unknown($codecType)"
    }

    class AudioConfig(val sampleRate: Int, val bits: Int, val channels: Int)

    class AudioService(
        val serviceId: Int,
        val streamType: Int,
        val codecType: Int,
        val configs: List<AudioConfig>,
    ) {
        val codecName: String get() = CODECS[codecType] ?: "unknown($codecType)"
        val streamName: String get() = STREAMS[streamType] ?: "unknown($streamType)"
    }

    /**
     * Every audio sink the head unit offers (media, guidance, system, telephony). Same
     * MediaSinkService as video, told apart by carrying `audio_type = 2` and
     * `repeated AudioConfiguration audio_configs = 3 { sampling_rate = 1; bits = 2; channels = 3 }`.
     */
    fun findAudioServices(response: ByteArray): List<AudioService> = buildList {
        for (channel in Wire.allBytes(Wire.fields(response), 1)) {
            val service = Wire.fields(channel)
            val id = Wire.varint(service, 1)?.toInt() ?: continue
            val sink = Wire.bytes(service, 3) ?: continue
            val sinkFields = Wire.fields(sink)
            val audioConfigs = Wire.allBytes(sinkFields, 3)
            if (audioConfigs.isEmpty()) continue
            add(
                AudioService(
                    serviceId = id,
                    streamType = Wire.varint(sinkFields, 2)?.toInt() ?: 0,
                    codecType = Wire.varint(sinkFields, 1)?.toInt() ?: 1,
                    configs = audioConfigs.map {
                        val f = Wire.fields(it)
                        AudioConfig(
                            sampleRate = Wire.varint(f, 1)?.toInt() ?: 48_000,
                            bits = Wire.varint(f, 2)?.toInt() ?: 16,
                            channels = Wire.varint(f, 3)?.toInt() ?: 2,
                        )
                    },
                ),
            )
        }
    }

    private val STREAMS = mapOf(0 to "NONE", 1 to "GUIDANCE", 2 to "SYSTEM_AUDIO", 3 to "MEDIA", 4 to "TELEPHONY")

    private val RESOLUTIONS = mapOf(
        1 to "800x480", 2 to "1280x720", 3 to "1920x1080", 4 to "2560x1440",
        5 to "3840x2160", 6 to "720x1280", 7 to "1080x1920", 8 to "1440x2560", 9 to "2160x3840",
    )
    private val PIXEL_SIZES = mapOf(
        1 to (800 to 480), 2 to (1280 to 720), 3 to (1920 to 1080), 4 to (2560 to 1440),
        5 to (3840 to 2160), 6 to (720 to 1280), 7 to (1080 to 1920), 8 to (1440 to 2560),
        9 to (2160 to 3840),
    )
    private val CODECS = mapOf(
        1 to "AUDIO_PCM", 2 to "AUDIO_AAC_LC", 3 to "VIDEO_H264_BP",
        4 to "AUDIO_AAC_LC_ADTS", 5 to "VIDEO_VP9", 6 to "VIDEO_AV1", 7 to "VIDEO_H265",
    )

    /**
     * The head unit's touchscreen, from InputSourceService (Service field 4):
     *   InputSourceService { keycodes = 1; repeated TouchScreen touchscreen = 2; ... }
     *   TouchScreen { required int32 width = 1; required int32 height = 2; type = 3; }
     *
     * Touch coordinates arrive in this space (1258x708 on the 2018 Uconnect) and must be
     * scaled to the projected video size before injection.
     */
    class InputService(val serviceId: Int, val touchWidth: Int, val touchHeight: Int)

    fun findInputService(response: ByteArray): InputService? {
        for (channel in Wire.allBytes(Wire.fields(response), 1)) {
            val service = Wire.fields(channel)
            val id = Wire.varint(service, 1)?.toInt() ?: continue
            val input = Wire.bytes(service, 4) ?: continue
            val touch = Wire.bytes(Wire.fields(input), 2) ?: continue
            val touchFields = Wire.fields(touch)
            val width = Wire.varint(touchFields, 1)?.toInt() ?: continue
            val height = Wire.varint(touchFields, 2)?.toInt() ?: continue
            return InputService(id, width, height)
        }
        return null
    }

    fun findVideoService(response: ByteArray): VideoService? {
        for (channel in Wire.allBytes(Wire.fields(response), 1)) {
            val service = Wire.fields(channel)
            val id = Wire.varint(service, 1)?.toInt() ?: continue
            val sink = Wire.bytes(service, 3) ?: continue

            val sinkFields = Wire.fields(sink)
            val videoConfigs = Wire.allBytes(sinkFields, 4)
            if (videoConfigs.isEmpty()) continue // audio sink, not video

            val codecType = Wire.varint(sinkFields, 1)?.toInt() ?: 0
            val configs = videoConfigs.map {
                val f = Wire.fields(it)
                VideoConfig(
                    resolution = Wire.varint(f, 1)?.toInt() ?: 0,
                    frameRate = Wire.varint(f, 2)?.toInt() ?: 0,
                    density = Wire.varint(f, 5)?.toInt() ?: 0,
                    widthMargin = Wire.varint(f, 3)?.toInt() ?: 0,
                    heightMargin = Wire.varint(f, 4)?.toInt() ?: 0,
                )
            }
            return VideoService(id, codecType, configs)
        }
        return null
    }

    /**
     * Who the head unit says it is, from the top-level strings of ServiceDiscoveryResponse.
     * Field numbers as seen from a 2018 Dodge Challenger: 2="Uconnect", 3="Dodge Challenger",
     * 4="18", 7="Delphi", 8="VP2_R Uconnect 7".
     */
    class HeadUnitInfo(
        val headUnitName: String,
        val carModel: String,
        val carYear: String,
        val headUnitMake: String,
        val headUnitModel: String,
    ) {
        override fun toString() =
            "name=\"$headUnitName\" car=\"$carModel\" year=\"$carYear\" " +
                "make=\"$headUnitMake\" model=\"$headUnitModel\""
    }

    fun findHeadUnitInfo(response: ByteArray): HeadUnitInfo {
        val fields = Wire.fields(response)
        fun text(number: Int) = Wire.bytes(fields, number)?.toString(Charsets.UTF_8).orEmpty()
        return HeadUnitInfo(text(2), text(3), text(4), text(7), text(8))
    }
}
