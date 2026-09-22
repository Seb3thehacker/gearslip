package app.seb3thehacker.gearslip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Parses the ServiceDiscoveryResponse the 2018 Uconnect actually sent, rebuilt field for field
 * from the decoded tree in `archive/spike/car-run-2026-09-16-run2.log`.
 *
 * The car offers two audio sinks - media on channel 5 and guidance on channel 6 - alongside the
 * video sink on channel 1 and a microphone source on channel 3. Picking the right one out of that
 * set is the whole job of [ServiceDiscovery.findAudioServices], and getting it wrong is silent:
 * audio simply never arrives.
 */
class ServiceDiscoveryAudioTest {

    private fun varint(value: Long): ByteArray = Protobuf.varint(value)

    private fun varintField(field: Int, value: Long) = Protobuf.varintField(field, value)

    private fun lenField(field: Int, body: ByteArray): ByteArray =
        varint(((field shl 3) or 2).toLong()) + varint(body.size.toLong()) + body

    /** AudioConfiguration { sampling_rate = 1; bits = 2; channels = 3 } */
    private fun audioConfig(rate: Int, bits: Int, channels: Int) =
        varintField(1, rate.toLong()) + varintField(2, bits.toLong()) + varintField(3, channels.toLong())

    /** Service { id = 1; media_sink_service = 3 } carrying an audio sink. */
    private fun audioSink(id: Int, codec: Int, audioType: Int, config: ByteArray) =
        lenField(
            1,
            varintField(1, id.toLong()) +
                lenField(3, varintField(1, codec.toLong()) + varintField(2, audioType.toLong()) + lenField(3, config)),
        )

    /** Channel 1: the H.264 video sink, which must not be mistaken for an audio one. */
    private fun videoSink(): ByteArray {
        val videoConfig = varintField(1, 2L) + varintField(2, 2L) + varintField(3, 0L) +
            varintField(4, 12L) + varintField(5, 240L) + varintField(6, 0L) + varintField(8, 10_000L)
        return lenField(
            1,
            varintField(1, 1L) + lenField(3, varintField(1, 3L) + lenField(4, videoConfig)),
        )
    }

    /** Channel 3: the car's microphone, a media *source* (field 5), not a sink. */
    private fun micSource(): ByteArray = lenField(
        1,
        varintField(1, 3L) + lenField(5, varintField(1, 1L) + lenField(2, audioConfig(16_000, 16, 1))),
    )

    private fun uconnectResponse(): ByteArray =
        videoSink() +
            micSource() +
            audioSink(id = 5, codec = 1, audioType = 3, config = audioConfig(48_000, 16, 2)) +
            audioSink(id = 6, codec = 1, audioType = 1, config = audioConfig(16_000, 16, 1)) +
            Protobuf.stringField(2, "Uconnect") +
            Protobuf.stringField(3, "Dodge Challenger") +
            Protobuf.stringField(4, "18")

    @Test
    fun `finds both of the car's audio sinks and neither the video sink nor the microphone`() {
        val sinks = ServiceDiscovery.findAudioServices(uconnectResponse())

        assertEquals(listOf(5, 6), sinks.map { it.serviceId })
        assertEquals(listOf("MEDIA", "GUIDANCE"), sinks.map { it.streamName })
    }

    @Test
    fun `the media sink is PCM at 48kHz stereo`() {
        val media = ServiceDiscovery.findAudioServices(uconnectResponse()).first { it.streamType == 3 }

        assertEquals("AUDIO_PCM", media.codecName)
        assertEquals(1, media.configs.size)
        assertEquals(48_000, media.configs[0].sampleRate)
        assertEquals(16, media.configs[0].bits)
        assertEquals(2, media.configs[0].channels)
    }

    @Test
    fun `the guidance sink is mono at 16kHz, so it must not be used for music`() {
        val guidance = ServiceDiscovery.findAudioServices(uconnectResponse()).first { it.streamType == 1 }

        assertEquals(16_000, guidance.configs[0].sampleRate)
        assertEquals(1, guidance.configs[0].channels)
    }

    @Test
    fun `the video sink is still found alongside the audio ones`() {
        assertNotNull(ServiceDiscovery.findVideoService(uconnectResponse()))
        assertEquals(1, ServiceDiscovery.findVideoService(uconnectResponse())!!.serviceId)
    }
}
