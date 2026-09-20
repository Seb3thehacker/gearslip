package app.seb3thehacker.gearslip

import android.os.Build
import app.seb3thehacker.gearslip.audio.AudioLink
import app.seb3thehacker.gearslip.car.CarEnvironment
import java.io.InputStream
import java.io.OutputStream

/**
 * Drives the phone side of the control-channel handshake far enough to answer one question:
 * does this head unit accept a self-signed certificate from the phone?
 *
 * The head unit initiates (aasdk's ControlServiceChannel::sendVersionRequest), so until
 * step 5 this is purely reactive:
 *
 *   1  HU -> phone  VersionRequest          id 1  plain
 *   2  phone -> HU  VersionResponse         id 2  plain
 *   3  both         EncapsulatedSSL         id 3  plain     (repeats until TLS completes)
 *   4  HU -> phone  AuthComplete            id 4  plain     <- the answer
 *   5  phone -> HU  ServiceDiscoveryRequest id 5  ENCRYPTED
 *   6  HU -> phone  ServiceDiscoveryResponse id 6 ENCRYPTED <- the confirmation
 */
class GearslipRunner(
    private val input: InputStream,
    private val output: OutputStream,
    private val identityProvider: () -> CertProvider.Identity,
    private val projection: Projection? = null,
    private val vehicleProfileFor: (ServiceDiscovery.HeadUnitInfo) -> VehicleProfile? = { null },
) {

    private enum class State { WAIT_VERSION, TLS_HANDSHAKE, WAIT_AUTH, WAIT_SDR, DONE }

    private val parser = Frames.Parser()
    private val assembler = Frames.Assembler()
    private lateinit var tls: PhoneTls

    @Volatile private var state = State.WAIT_VERSION
    @Volatile private var lastProgress = System.currentTimeMillis()
    @Volatile private var running = true

    @Volatile private var videoChannelId: Int = -1
    private var videoConfigs: List<ServiceDiscovery.VideoConfig> = emptyList()
    private var selectedConfigIndex: Int? = null
    private var vehicleProfile: VehicleProfile? = null
    @Volatile private var videoStarted = false
    private var videoSource: VideoSource? = null
    @Volatile private var inFlight = 0
    @Volatile private var maxUnacked = 4
    @Volatile private var framesSent = 0
    @Volatile private var dropped = 0
    @Volatile private var inputChannelId: Int = -1
    @Volatile private var audioLink: AudioLink? = null
    private var touchWidth = 0
    private var touchHeight = 0
    private var touchesSeen = 0
    @Volatile private var acksSeen = 0
    @Volatile private var lastAckAt = 0L
    @Volatile private var splitMessages = 0
    @Volatile private var resyncRequested = false
    @Volatile private var resyncAttempts = 0
    @Volatile private var lastKeyframeSentAt = 0L

    fun run() {
        GearslipLog.i("--- spike run starting ---")
        SessionStatus.connecting("Connected to the head unit")
        GearslipLog.i("device=${Build.MANUFACTURER} ${Build.MODEL}  android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")

        val identity = identityProvider()
        val cert = identity.certificate
        GearslipLog.i("phone certificate source: ${identity.source}")
        GearslipLog.i("  subject = ${cert.subjectX500Principal}")
        GearslipLog.i("  issuer  = ${cert.issuerX500Principal}")
        GearslipLog.i("  serial  = ${cert.serialNumber}  valid ${cert.notBefore}..${cert.notAfter}")
        tls = PhoneTls(identity.keyStore, identity.password)

        startWatchdog()

        val chunk = ByteArray(16 * 1024) // AOAP: one read returns one bulk transfer
        try {
            while (running) {
                val read = input.read(chunk)
                if (read < 0) {
                    GearslipLog.w("head unit closed the connection (EOF) while in state $state")
                    reportDisconnect()
                    return
                }
                if (read == 0) continue
                parser.append(chunk, read)
                while (true) {
                    val frame = parser.next() ?: break
                    handleFrame(frame)
                }
            }
        } catch (t: Throwable) {
            GearslipLog.e("transport failed in state $state", t)
            reportDisconnect()
        } finally {
            running = false
            GearslipLog.flush()
        }
    }

    fun stop() {
        running = false
        videoSource?.stop()
        videoSource = null
        audioLink?.close()
        audioLink = null
        projection?.onProjectionStopped()
        SessionStatus.disconnected()
    }

    private fun handleFrame(frame: Frames.Frame) {
        val payload = if (frame.encrypted) {
            if (!tls.handshakeComplete) {
                GearslipLog.w("encrypted frame arrived before the handshake finished - ignoring")
                return
            }
            tls.decrypt(frame.payload)
        } else {
            frame.payload
        }

        val message = assembler.offer(frame, payload) ?: return
        if (message.size < 2) {
            GearslipLog.w("runt message on channel ${frame.channel}")
            return
        }

        val messageId = ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)
        val body = message.copyOfRange(2, message.size)

        lastProgress = System.currentTimeMillis()

        if (frame.channel != Frames.CHANNEL_CONTROL) {
            if (frame.channel == videoChannelId) {
                onVideoMessage(messageId, body)
            } else if (frame.channel == inputChannelId) {
                onInputMessage(messageId, body)
            } else if (frame.channel == audioLink?.channelId) {
                audioLink?.onMessage(messageId, body)
            } else {
                GearslipLog.i("ignoring message id $messageId on channel ${frame.channel}")
            }
            return
        }

        when (messageId) {
            MSG_VERSION_REQUEST -> onVersionRequest(body)
            MSG_ENCAPSULATED_SSL -> onHandshake(body)
            MSG_AUTH_COMPLETE -> onAuthComplete(body)
            MSG_SERVICE_DISCOVERY_RESPONSE -> onServiceDiscoveryResponse(body)
            MSG_PING_REQUEST -> onPingRequest(body)
            AudioLink.MSG_FOCUS_RESPONSE -> audioLink?.onFocus(Protobuf.readInt32Field(body, 1) ?: 0)
            else -> GearslipLog.i("unhandled control message id=$messageId (${body.size} bytes) - continuing")
        }
    }

    private fun onVersionRequest(body: ByteArray) {
        if (body.size < 4) {
            GearslipLog.w("VersionRequest too short (${body.size} bytes)")
            return
        }
        val major = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val minor = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
        GearslipLog.i("<- VersionRequest: head unit speaks $major.$minor")

        // Echo the head unit's own version with STATUS_SUCCESS (0). Echoing rather than
        // asserting 1.6 is the permissive choice: we only need to reach service discovery,
        // not to honour whatever that version implies.
        val response = byteArrayOf(
            ((major shr 8) and 0xFF).toByte(), (major and 0xFF).toByte(),
            ((minor shr 8) and 0xFF).toByte(), (minor and 0xFF).toByte(),
            0, 0, // STATUS_SUCCESS
        )
        send(MSG_VERSION_RESPONSE, response, encrypted = false)
        GearslipLog.i("-> VersionResponse: $major.$minor status=0 (STATUS_SUCCESS)")
        state = State.TLS_HANDSHAKE
        SessionStatus.connecting("Securing the link")
        GearslipLog.i("awaiting ClientHello - the head unit is the TLS client, we are the server")
    }

    private fun onHandshake(body: ByteArray) {
        GearslipLog.i("<- EncapsulatedSSL (${body.size} bytes)")
        val replies = try {
            tls.pumpHandshake(body)
        } catch (t: Throwable) {
            GearslipLog.e("TLS handshake threw", t)
            GearslipLog.verdict(
                "NOT VIABLE (TLS rejected)",
                "The head unit aborted the TLS handshake. A bad_certificate / unknown_ca / " +
                    "handshake_failure alert here means it validates the phone's certificate.",
            )
            SessionStatus.failed(
                "Head unit rejected the certificate",
                "The head unit aborted the secure handshake. It validates the phone's certificate.",
            )
            state = State.DONE
            return
        }
        replies.forEach {
            send(MSG_ENCAPSULATED_SSL, it, encrypted = false)
            GearslipLog.i("-> EncapsulatedSSL (${it.size} bytes)")
        }
        if (tls.handshakeComplete && state == State.TLS_HANDSHAKE) {
            state = State.WAIT_AUTH
            GearslipLog.i("awaiting AuthComplete - this carries the certificate verdict")
        }
    }

    private fun onAuthComplete(body: ByteArray) {
        val status = Protobuf.readInt32Field(body, 1)
        GearslipLog.i("<- AuthComplete: status=$status")

        when (status) {
            0 -> {
                GearslipLog.i("STATUS_SUCCESS - certificate accepted; confirming with service discovery")
                state = State.WAIT_SDR
                SessionStatus.connecting("Certificate accepted - discovering services")
                sendServiceDiscoveryRequest()
            }
            -2 -> {
                GearslipLog.verdict(
                    "NOT VIABLE (STATUS_CERTIFICATE_ERROR)",
                    "The head unit explicitly rejected the phone's self-signed certificate. " +
                        "This is a definitive no - it validates the phone against a trust chain " +
                        "we cannot issue from.",
                )
                SessionStatus.failed(
                    "Head unit rejected the certificate",
                    "The head unit refused the phone's certificate (certificate error).",
                )
                state = State.DONE
            }
            -3 -> {
                GearslipLog.verdict(
                    "NOT VIABLE (STATUS_AUTHENTICATION_FAILURE)",
                    "Authentication rejected. Broader than a pure certificate error, but the " +
                        "practical answer is the same.",
                )
                SessionStatus.failed(
                    "Head unit rejected the certificate",
                    "Authentication failed. The head unit does not trust the certificate in use.",
                )
                state = State.DONE
            }
            else -> {
                GearslipLog.verdict(
                    "INCONCLUSIVE (AuthComplete status=$status)",
                    "Unexpected status. Check MessageStatus.proto in aasdk for the meaning " +
                        "before drawing any conclusion.",
                )
                SessionStatus.failed("Unexpected response", "The head unit answered authentication with status $status.")
                state = State.DONE
            }
        }
    }

    private fun sendServiceDiscoveryRequest() {
        val body = Protobuf.stringField(4, "Gearslip") + // label_text
            Protobuf.stringField(5, Build.MODEL)        // device_name
        send(MSG_SERVICE_DISCOVERY_REQUEST, body, encrypted = true)
        GearslipLog.i("-> ServiceDiscoveryRequest (encrypted)")
    }

    private fun onServiceDiscoveryResponse(body: ByteArray) {
        GearslipLog.i("<- ServiceDiscoveryResponse (${body.size} bytes, decrypted successfully)")
        GearslipLog.i("head unit describes itself as:\n" + Protobuf.describe(body))
        GearslipLog.verdict(
            "VIABLE",
            "The head unit accepted the presented phone certificate, completed TLS, and is " +
                "advertising its channels over the encrypted session. A phone-side client is " +
                "possible against THIS unit - portability to newer units is still unproven.",
        )
        state = State.DONE
        val info = ServiceDiscovery.findHeadUnitInfo(body)
        GearslipLog.i("head unit: $info")
        vehicleProfile = runCatching { vehicleProfileFor(info) }.getOrNull()
        val profile = vehicleProfile
        if (profile != null) {
            GearslipLog.i("vehicle profile matched: \"${profile.name}\" resolution=${profile.resolution} insets=${profile.insets}")
        } else {
            GearslipLog.i("no vehicle profile matched - default display settings (add one to vehicles.json)")
        }
        CarEnvironment.setVehicle(profile?.name ?: "Unknown vehicle", profile?.insets ?: Insets.NONE)
        SessionStatus.connecting("Starting video")

        startVideoChannel(body)
    }

    // --- Phase A: video channel bring-up (no encoder yet) -------------------------------
    //
    // Inverting aasdk's head-unit video sink (src/Channel/MediaSink/Video/
    // VideoMediaSinkService.cpp): every message that sink *receives* is one we must *send*.
    //
    //   -> CHANNEL_OPEN_REQUEST   <- CHANNEL_OPEN_RESPONSE
    //   -> SETUP                  <- CONFIG   (status, max_unacked, configuration_indices)
    //   -> VIDEO_FOCUS_REQUEST    <- VIDEO_FOCUS_NOTIFICATION
    //
    // Reaching VIDEO_FOCUS_NOTIFICATION means the head unit is waiting for frames, which is
    // the Phase A milestone. Phase B then sends CODEC_CONFIG + DATA.

    private fun startVideoChannel(serviceDiscoveryResponse: ByteArray) {
        val video = ServiceDiscovery.findVideoService(serviceDiscoveryResponse)
        if (video == null) {
            GearslipLog.w("no video service in the discovery response - cannot start Phase A")
            return
        }
        videoChannelId = video.serviceId
        videoConfigs = video.configs

        GearslipLog.i("video service: channel=${video.serviceId} codec=${video.codecName}")
        video.configs.forEachIndexed { index, config ->
            GearslipLog.i("  config[$index] = $config")
        }
        if (video.codecType != CODEC_H264_BP) {
            GearslipLog.w("head unit wants ${video.codecName}, not H264_BP - Phase B must match this")
        }

        openChannel(video.serviceId)
        startAudioChannel(serviceDiscoveryResponse)

        val input = ServiceDiscovery.findInputService(serviceDiscoveryResponse)
        if (input == null) {
            GearslipLog.w("no input service advertised - projection will be output-only")
        } else {
            inputChannelId = input.serviceId
            touchWidth = input.touchWidth
            touchHeight = input.touchHeight
            GearslipLog.i(
                "input service: channel=${input.serviceId} touchscreen=" +
                    "${input.touchWidth}x${input.touchHeight}",
            )
            openChannel(input.serviceId)
        }
    }

    /** Media audio rides its own sink; guidance, system and telephony sinks are left closed. */
    private fun startAudioChannel(serviceDiscoveryResponse: ByteArray) {
        val sinks = ServiceDiscovery.findAudioServices(serviceDiscoveryResponse)
        sinks.forEach { sink ->
            GearslipLog.i(
                "audio sink: channel=${sink.serviceId} stream=${sink.streamName} codec=${sink.codecName} " +
                    sink.configs.joinToString(prefix = "[", postfix = "]") { "${it.sampleRate}Hz/${it.bits}bit/x${it.channels}" },
            )
        }
        val media = sinks.firstOrNull { it.streamType == STREAM_MEDIA } ?: run {
            GearslipLog.w("no media audio sink advertised - media apps will play on the phone only")
            return
        }
        audioLink = AudioLink(
            media,
            sendOnChannel = { id, body -> send(id, body, encrypted = true, channel = media.serviceId) },
            sendControl = { id, body -> send(id, body, encrypted = true) },
        )
        openChannel(media.serviceId)
    }

    private fun openChannel(serviceId: Int) {
        val request = Protobuf.varintField(1, 0L) +          // priority (sint32 zigzag: 0 -> 0)
            Protobuf.varintField(2, serviceId.toLong())      // service_id
        send(
            MSG_CHANNEL_OPEN_REQUEST, request, encrypted = true,
            channel = serviceId, messageType = Frames.MESSAGE_CONTROL,
        )
        GearslipLog.i("-> ChannelOpenRequest(service_id=$serviceId) on channel $serviceId")
    }

    /**
     * Touch events arrive **already in projected video coordinates**, not in the head unit's
     * native touchscreen space.
     *
     * Confirmed on the 2018 Uconnect: its panel is advertised as 1258x708, but with 800x480
     * projected the raw x never exceeded 791 and raw y never exceeded ~351 even when tapping
     * the far edges. Scaling by touchscreen/video (as an earlier version did) compressed the
     * right-hand third of the screen away entirely - taps on a right-edge button landed on
     * the middle one. The head unit maps its own panel onto our video before reporting.
     *
     * So: inject as-is, clamped to the video bounds. The advertised touchscreen size is kept
     * only for diagnostics.
     */
    private fun onInputMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE ->
                GearslipLog.i("<- ChannelOpenResponse (input): status=${Protobuf.readInt32Field(body, 1)}")

            MSG_INPUT_REPORT -> {
                val report = Wire.fields(body)
                val touch = Wire.bytes(report, 3) ?: Wire.bytes(report, 7)
                if (touch == null) {
                    GearslipLog.i("<- InputReport with no touch payload (key or rotary event)")
                    return
                }
                val touchFields = Wire.fields(touch)
                val action = Wire.varint(touchFields, 3)?.toInt() ?: 0
                val pointers = Wire.allBytes(touchFields, 1)
                if (pointers.isEmpty()) return
                if (pointers.size > 1 && touchesSeen < 3) {
                    GearslipLog.i("(multi-touch: ${pointers.size} pointers; using the first)")
                }

                val first = Wire.fields(pointers[0])
                val rawX = Wire.varint(first, 1)?.toInt() ?: return
                val rawY = Wire.varint(first, 2)?.toInt() ?: return

                val size = videoConfigs.getOrNull(selectedConfigIndex ?: 0)?.pixelSize()
                val x = clamp(rawX, size?.first)
                val y = clamp(rawY, size?.second)

                touchesSeen++
                if (touchesSeen <= 10 || action == ACTION_DOWN || action == ACTION_UP) {
                    val note = if (x != rawX.toFloat() || y != rawY.toFloat()) " (clamped)" else ""
                    GearslipLog.i("<- Touch action=$action at ($rawX,$rawY)$note")
                }
                projection?.onTouch(action, x, y)
            }

            else -> GearslipLog.i("<- unhandled input message id=$messageId (${body.size} bytes)")
        }
    }

    private fun onVideoMessage(messageId: Int, body: ByteArray) {
        when (messageId) {
            MSG_CHANNEL_OPEN_RESPONSE -> {
                val status = Protobuf.readInt32Field(body, 1)
                GearslipLog.i("<- ChannelOpenResponse: status=$status")
                if (status != 0) {
                    GearslipLog.verdict(
                        "PHASE A FAILED (channel open rejected)",
                        "The head unit refused to open the video channel (status=$status).",
                    )
                    SessionStatus.failed("Video channel refused", "The head unit would not open the video channel (status $status).")
                    return
                }
                // Setup { required MediaCodecType type = 1 }
                send(MSG_MEDIA_SETUP, Protobuf.varintField(1, CODEC_H264_BP.toLong()),
                    encrypted = true, channel = videoChannelId)
                GearslipLog.i("-> Setup(codec=VIDEO_H264_BP)")
            }

            MSG_MEDIA_CONFIG -> {
                val fields = Wire.fields(body)
                val status = Wire.varint(fields, 1)?.toInt()
                val maxUnackedFromConfig = Wire.varint(fields, 2)?.toInt()
                val indices = fields.filter { it.number == 3 && it.wireType == 0 }.map { it.varint }
                GearslipLog.i(
                    "<- Config: status=$status (1=WAIT 2=READY) max_unacked=$maxUnackedFromConfig " +
                        "configuration_indices=$indices",
                )
                if (maxUnackedFromConfig != null) maxUnacked = maxOf(1, maxUnackedFromConfig)
                // Prefer the smallest offered resolution for the first attempt: fewer pixels,
                // fewer ways for a first encode to go wrong.
                val offered = indices.map { it.toInt() }
                val preferred = vehicleProfile?.resolution?.let { want ->
                    offered.firstOrNull { videoConfigs.getOrNull(it)?.resolutionName == want }
                }
                selectedConfigIndex = preferred ?: offered.minByOrNull { idx ->
                    videoConfigs.getOrNull(idx)?.pixelCount() ?: Int.MAX_VALUE
                } ?: offered.firstOrNull()
                GearslipLog.i("selected config index $selectedConfigIndex from offered $offered")
                // VideoFocusRequestNotification { mode = 2; reason = 3 }
                val focus = Protobuf.varintField(2, VIDEO_FOCUS_PROJECTED.toLong()) +
                    Protobuf.varintField(3, 1L)
                send(MSG_VIDEO_FOCUS_REQUEST, focus, encrypted = true, channel = videoChannelId)
                GearslipLog.i("-> VideoFocusRequest(mode=PROJECTED)")
            }

            MSG_VIDEO_FOCUS_NOTIFICATION -> {
                val fields = Wire.fields(body)
                GearslipLog.i("<- VideoFocusNotification: mode=${Wire.varint(fields, 1)}")
                // The head unit repeats this while it waits for frames; only act once.
                if (!videoStarted) {
                    videoStarted = true
                    GearslipLog.i("PHASE A COMPLETE - video focus granted; starting video source")
                    startVideoSource()
                }
            }

            MSG_MEDIA_ACK -> {
                val fields = Wire.fields(body)
                val acked = Wire.varint(fields, 2)?.toInt() ?: 1
                inFlight = maxOf(0, inFlight - maxOf(1, acked))
                acksSeen++
                lastAckAt = System.currentTimeMillis()
                if (framesSent <= 3 || framesSent % 60 == 0) {
                    GearslipLog.i("<- MediaAck(ack=$acked) inFlight=$inFlight after $framesSent frames")
                }
            }

            else -> GearslipLog.i("<- unhandled video message id=$messageId (${body.size} bytes)")
        }
    }

    // --- Phase B: encode and stream H.264 ----------------------------------------------
    //
    //   -> Start { session_id, configuration_index }
    //   -> CodecConfig (raw SPS/PPS, NO timestamp - aasdk routes it to onMediaIndication)
    //   -> Data (8-byte big-endian timestamp, then the H.264 access unit)
    //   <- Ack { session_id, ack }   - at most max_unacked frames may be outstanding

    private fun startVideoSource() {
        lastKeyframeSentAt = System.currentTimeMillis() // frame #1 is always a keyframe
        val index = selectedConfigIndex ?: 0
        val config = videoConfigs.getOrNull(index)
        if (config == null) {
            GearslipLog.e("no video config at index $index - cannot start the encoder")
            return
        }
        val (width, height) = config.pixelSize() ?: run {
            GearslipLog.e("unsupported resolution ${config.resolutionName}")
            return
        }

        val start = Protobuf.varintField(1, SESSION_ID.toLong()) +
            Protobuf.varintField(2, index.toLong())
        send(MSG_MEDIA_START, start, encrypted = true, channel = videoChannelId)
        GearslipLog.i("-> Start(session_id=$SESSION_ID, configuration_index=$index -> $config)")

        val useProjection = projection != null
        videoSource = VideoSource(
            width = width,
            height = height,
            frameRate = if (config.frameRate == 1) 60 else 30,
            mode = if (useProjection) VideoSource.Mode.SURFACE else VideoSource.Mode.TEST_CARD,
            onCodecConfig = { csd ->
                send(MSG_MEDIA_CODEC_CONFIG, csd, encrypted = true, channel = videoChannelId)
                GearslipLog.i("-> CodecConfig (${csd.size} bytes SPS/PPS)")
                GearslipLog.hex("   csd", csd, limit = 64)
            },
            onFrame = { data, presentationTimeUs, keyFrame ->
                sendVideoFrame(data, presentationTimeUs, keyFrame)
            },
        ).also { source ->
            runCatching {
                source.start()
                val surface = source.inputSurface
                if (projection != null && surface != null) {
                    projection.onSurfaceReady(surface, width, height, config.density)
                    SessionStatus.projecting()
                }
            }.onFailure { e -> GearslipLog.e("encoder start failed", e) }
        }
    }

    private fun sendVideoFrame(data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) {
        // Flow control: the head unit told us max_unacked in its Config reply.
        if (inFlight >= maxUnacked) {
            // A dropped or never-sent ack would otherwise wedge the stream forever, which
            // looks identical to a frozen picture. Assume the window cleared and carry on.
            val since = System.currentTimeMillis() - lastAckAt
            if (since > ACK_STALL_MS) {
                GearslipLog.w("no ack for ${since}ms with $inFlight in flight - resetting the window")
                inFlight = 0
            } else {
                dropped++
                if (dropped % 30 == 1) {
                    GearslipLog.w("dropping frame - $inFlight in flight (max $maxUnacked)")
                }
                // A dropped frame breaks the decoder's reference chain on the head unit -
                // every frame after it is a P-frame assuming a picture that never arrived.
                // Without a resync the picture free-runs on corrupted state indefinitely,
                // which is the smeared/ghosted look.
                //
                // Ask on EVERY drop, not just the first in a streak: the resync keyframe we
                // asked for is itself just another frame, and can be dropped by this exact
                // same check. A one-shot request that gets swallowed by the burst that
                // provoked it leaves the stream corrupted for good, with no further request
                // ever made - confirmed on the road (docs/spike/phase-c-stuck-resync-2026-09-17.log,
                // dropped=27 in one burst, video dead at 0fps for 100+s afterward). Repeated
                // setParameters(REQUEST_SYNC_FRAME) calls before one lands are harmless - it
                // just keeps the "next frame is a keyframe" flag set.
                resyncRequested = true
                resyncAttempts++
                videoSource?.requestSyncFrame()
                return
            }
        }
        val timestamp = ByteArray(8)
        for (i in 0 until 8) {
            timestamp[i] = ((presentationTimeUs shr ((7 - i) * 8)) and 0xFF).toByte()
        }
        if (keyFrame) {
            if (resyncRequested) {
                GearslipLog.i("resync keyframe delivered after $resyncAttempts attempt(s)")
            }
            resyncRequested = false
            resyncAttempts = 0
            lastKeyframeSentAt = System.currentTimeMillis()
        }
        try {
            send(MSG_MEDIA_DATA, timestamp + data, encrypted = true, channel = videoChannelId)
        } catch (e: java.io.IOException) {
            if (running) GearslipLog.w("video send failed, stopping the encoder: ${e.message}")
            running = false
            videoSource?.stop()
            return
        }
        inFlight++
        framesSent++
        if (framesSent <= 3 || keyFrame && framesSent % 30 == 0) {
            GearslipLog.i(
                "-> Data frame #$framesSent (${data.size} bytes, keyFrame=$keyFrame, " +
                    "ptsUs=$presentationTimeUs)",
            )
        }
    }

    private fun clamp(value: Int, limit: Int?): Float = when {
        limit == null -> value.toFloat()
        value < 0 -> 0f
        value >= limit -> (limit - 1).toFloat()
        else -> value.toFloat()
    }

    private fun onPingRequest(body: ByteArray) {
        val timestamp = Protobuf.readInt32Field(body, 1)?.toLong() ?: System.nanoTime()
        send(MSG_PING_RESPONSE, Protobuf.varintField(1, timestamp), encrypted = tls.handshakeComplete)
        GearslipLog.i("<- PingRequest / -> PingResponse (keeping the session alive)")
    }

    private fun send(
        messageId: Int,
        body: ByteArray,
        encrypted: Boolean,
        channel: Int = Frames.CHANNEL_CONTROL,
        messageType: Int = Frames.MESSAGE_SPECIFIC,
    ) {
        val payload = byteArrayOf(
            ((messageId shr 8) and 0xFF).toByte(), (messageId and 0xFF).toByte(),
        ) + body

        synchronized(output) {
            if (payload.size <= Frames.MAX_FRAME_PAYLOAD) {
                val framePayload = if (encrypted) tls.encrypt(payload) else payload
                output.write(Frames.build(channel, encrypted, framePayload, messageType))
            } else {
                // Split exactly as aasdk does: chunk the *plaintext*, encrypt each chunk
                // separately, and carry the total plaintext length in the FIRST frame.
                // Video keyframes for anything busier than a test card exceed 16 KB routinely.
                var offset = 0
                while (offset < payload.size) {
                    val size = minOf(Frames.MAX_FRAME_PAYLOAD, payload.size - offset)
                    val chunk = payload.copyOfRange(offset, offset + size)
                    val frameType = when {
                        offset == 0 -> Frames.TYPE_FIRST
                        offset + size >= payload.size -> Frames.TYPE_LAST
                        else -> Frames.TYPE_MIDDLE
                    }
                    val framePayload = if (encrypted) tls.encrypt(chunk) else chunk
                    output.write(
                        Frames.build(
                            channel, encrypted, framePayload, messageType, frameType,
                            if (frameType == Frames.TYPE_FIRST) payload.size else null,
                        ),
                    )
                    offset += size
                }
                splitMessages++
            }
            output.flush()
        }
    }

    private fun reportDisconnect() {
        when (state) {
            State.DONE -> SessionStatus.disconnected()
            State.WAIT_VERSION -> SessionStatus.failed(
                "Head unit never started talking",
                "The connection opened but the head unit sent nothing. Check the phone is set to use Gearslip for this USB accessory.",
            )
            State.TLS_HANDSHAKE, State.WAIT_AUTH -> SessionStatus.failed(
                "Connection dropped during setup",
                "The head unit disconnected while securing the link. This usually means it rejected the certificate.",
            )
            State.WAIT_SDR -> SessionStatus.failed(
                "Connection dropped after authentication",
                "The certificate was accepted but the head unit disconnected before finishing setup.",
            )
        }
        when (state) {
            State.WAIT_VERSION -> GearslipLog.verdict(
                "INCONCLUSIVE (no VersionRequest)",
                "The head unit never opened the control channel. This is a connection problem, " +
                    "not a certificate verdict - check that the accessory was actually claimed.",
            )
            State.TLS_HANDSHAKE -> GearslipLog.verdict(
                "LIKELY NOT VIABLE (dropped during TLS)",
                "The unit disconnected mid-handshake without sending an alert we could read. " +
                    "Rejection is the likeliest reading, but re-run once before trusting it.",
            )
            State.WAIT_AUTH -> GearslipLog.verdict(
                "INCONCLUSIVE (dropped after TLS, before AuthComplete)",
                "TLS completed but the unit disconnected before stating a verdict. Re-run.",
            )
            State.WAIT_SDR -> GearslipLog.verdict(
                "INCONCLUSIVE (accepted, then dropped before service discovery)",
                "AuthComplete said success, so the certificate was accepted - but our " +
                    "ServiceDiscoveryRequest drew no reply. Suspect our own encryption or " +
                    "framing rather than the head unit.",
            )
            State.DONE -> GearslipLog.i("connection closed after the run completed")
        }
    }

    private fun startWatchdog() {
        Thread {
            var lastFrames = 0
            while (running) {
                Thread.sleep(5_000)
                if (!running) break

                if (state != State.DONE) {
                    val idle = System.currentTimeMillis() - lastProgress
                    if (idle > 10_000) GearslipLog.w("stalled in state $state for ${idle / 1000}s")
                }

                // Stream health. Without this a drive cannot tell "streaming fine" from
                // "stalled after three frames" - the per-frame logging is deliberately sparse.
                if (videoStarted) {
                    val sent = framesSent
                    val fps = (sent - lastFrames) / 5.0
                    lastFrames = sent
                    GearslipLog.i(
                        "video: ${"%.1f".format(fps)} fps over 5s | sent=$sent dropped=$dropped " +
                            "acked=$acksSeen inFlight=$inFlight split=$splitMessages",
                    )

                    // Self-healing safety net: force a fresh keyframe periodically regardless
                    // of whether we think one is owed. Protects against any future case where
                    // our own drop/resync bookkeeping is wrong or a corruption slips through
                    // some other path - a real decoder desync should never outlive this.
                    val idleSinceKeyframe = System.currentTimeMillis() - lastKeyframeSentAt
                    if (idleSinceKeyframe > PERIODIC_KEYFRAME_MS) {
                        videoSource?.requestSyncFrame()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private companion object {
        // protobuf/aap_protobuf/service/control/ControlMessageType.proto:7-17
        const val MSG_VERSION_REQUEST = 1
        const val MSG_VERSION_RESPONSE = 2
        const val MSG_ENCAPSULATED_SSL = 3
        const val MSG_AUTH_COMPLETE = 4
        const val MSG_SERVICE_DISCOVERY_REQUEST = 5
        const val MSG_SERVICE_DISCOVERY_RESPONSE = 6
        const val MSG_PING_REQUEST = 11
        const val MSG_PING_RESPONSE = 12
        const val MSG_CHANNEL_OPEN_REQUEST = 7
        const val MSG_CHANNEL_OPEN_RESPONSE = 8

        // aap_protobuf/service/media/.../MediaMessageId
        const val MSG_MEDIA_DATA = 0
        const val MSG_MEDIA_CODEC_CONFIG = 1
        const val MSG_MEDIA_SETUP = 32768
        const val MSG_MEDIA_START = 32769
        const val MSG_MEDIA_STOP = 32770
        const val MSG_MEDIA_CONFIG = 32771
        const val MSG_MEDIA_ACK = 32772
        const val MSG_VIDEO_FOCUS_REQUEST = 32775
        const val MSG_VIDEO_FOCUS_NOTIFICATION = 32776

        const val STREAM_MEDIA = 3
        const val CODEC_H264_BP = 3
        const val VIDEO_FOCUS_PROJECTED = 1
        const val SESSION_ID = 1
        const val MSG_INPUT_REPORT = 32769
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACK_STALL_MS = 2_000L
        const val PERIODIC_KEYFRAME_MS = 5_000L
    }
}
