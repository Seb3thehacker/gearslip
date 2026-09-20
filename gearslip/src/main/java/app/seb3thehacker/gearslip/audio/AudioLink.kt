package app.seb3thehacker.gearslip.audio

import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.ServiceDiscovery
import app.seb3thehacker.gearslip.Wire

/**
 * The phone end of the head unit's media audio sink, the audio twin of the video channel:
 *
 *   -> CHANNEL_OPEN_REQUEST   <- CHANNEL_OPEN_RESPONSE
 *   -> Setup { PCM }          <- Config { status, max_unacked, configuration_indices }
 *   -> AudioFocusRequest      <- AudioFocusResponse           (control channel)
 *   -> Start { session_id, configuration_index }
 *   -> Data { 8-byte timestamp, PCM }...   <- Ack { session_id, ack }
 *   -> Stop
 *
 * Inverted from aasdk's AudioMediaSinkService. Bench-verified only as far as capture goes:
 * nothing on this side of the wire has met a head unit yet.
 */
class AudioLink(
    private val service: ServiceDiscovery.AudioService,
    private val sendOnChannel: (messageId: Int, body: ByteArray) -> Unit,
    private val sendControl: (messageId: Int, body: ByteArray) -> Unit,
) : CarAudio.Link {

    private var configIndex = 0
    @Volatile private var ready = false
    @Volatile private var started = false
    @Volatile private var inFlight = 0
    @Volatile private var maxUnacked = 8
    @Volatile private var lastAckAt = 0L
    private var chunks = 0

    private val config get() = service.configs.getOrNull(configIndex) ?: service.configs.firstOrNull()
    override val sampleRate get() = config?.sampleRate ?: 48_000
    override val channels get() = config?.channels ?: 2

    val channelId get() = service.serviceId

    fun opened(status: Int) {
        if (status != 0) {
            GearslipLog.w("audio: the head unit refused the media audio channel (status $status)")
            return
        }
        sendOnChannel(MSG_SETUP, Protobuf.varintField(1, service.codecType.toLong()))
        GearslipLog.i("audio: -> Setup(codec=${service.codecName})")
    }

    fun onMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> opened(Protobuf.readInt32Field(body, 1) ?: 0)

            MSG_CONFIG -> {
                val fields = Wire.fields(body)
                val status = Wire.varint(fields, 1)?.toInt()
                val window = Wire.varint(fields, 2)?.toInt()
                val indices = fields.filter { it.number == 3 && it.wireType == 0 }.map { it.varint.toInt() }
                GearslipLog.i("audio: <- Config status=$status max_unacked=$window indices=$indices")
                if (window != null) maxUnacked = maxOf(1, window)
                configIndex = indices.firstOrNull() ?: 0
                if (status == STATUS_READY) {
                    ready = true
                    GearslipLog.i("audio: ready - ${sampleRate}Hz x$channels ${config?.bits}bit")
                    CarAudio.attach(this)
                }
            }

            MSG_ACK -> {
                val acked = Wire.varint(Wire.fields(body), 2)?.toInt() ?: 1
                inFlight = maxOf(0, inFlight - maxOf(1, acked))
                lastAckAt = System.currentTimeMillis()
            }

            else -> GearslipLog.i("audio: <- unhandled message id=$messageId (${body.size} bytes)")
        }
    }

    fun onFocus(state: Int) {
        GearslipLog.i("audio: <- AudioFocus state=$state (1=GAIN, 2=GAIN_TRANSIENT, 3=LOSS, 4=LOSS_TRANSIENT)")
    }

    override fun start() {
        if (!ready || started) return
        sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_GAIN.toLong()))
        sendOnChannel(
            MSG_START,
            Protobuf.varintField(1, SESSION_ID.toLong()) + Protobuf.varintField(2, configIndex.toLong()),
        )
        inFlight = 0
        lastAckAt = System.currentTimeMillis()
        started = true
        GearslipLog.i("audio: -> AudioFocusRequest(GAIN), Start(config=$configIndex)")
    }

    override fun stop() {
        if (!started) return
        started = false
        runCatching {
            sendOnChannel(MSG_STOP, Protobuf.varintField(1, SESSION_ID.toLong()))
            sendControl(MSG_FOCUS_REQUEST, Protobuf.varintField(1, FOCUS_RELEASE.toLong()))
        }
        GearslipLog.i("audio: -> Stop, AudioFocusRequest(RELEASE) after $chunks chunks")
        chunks = 0
    }

    override fun write(pcm: ByteArray, size: Int) {
        if (!started) return
        if (inFlight >= maxUnacked) {
            // A lost ack must not silence the stream for good; after a stall assume the window cleared.
            if (System.currentTimeMillis() - lastAckAt > STALL_MS) inFlight = 0 else return
        }
        val timestamp = ByteArray(8)
        val micros = System.nanoTime() / 1_000
        for (i in 0 until 8) timestamp[i] = ((micros shr ((7 - i) * 8)) and 0xFF).toByte()
        try {
            sendOnChannel(MSG_DATA, timestamp + pcm.copyOf(size))
        } catch (e: java.io.IOException) {
            started = false
            return
        }
        inFlight++
        chunks++
    }

    fun close() {
        stop()
        ready = false
        CarAudio.detach(this)
    }

    companion object {
        const val MSG_FOCUS_REQUEST = 18
        const val MSG_FOCUS_RESPONSE = 19
        private const val MSG_CHANNEL_OPEN_RESPONSE = 8
        private const val MSG_DATA = 0
        private const val MSG_SETUP = 32768
        private const val MSG_START = 32769
        private const val MSG_STOP = 32770
        private const val MSG_CONFIG = 32771
        private const val MSG_ACK = 32772
        private const val STATUS_READY = 2
        private const val FOCUS_GAIN = 1
        private const val FOCUS_RELEASE = 4
        private const val SESSION_ID = 2
        private const val STALL_MS = 2_000L
    }
}
