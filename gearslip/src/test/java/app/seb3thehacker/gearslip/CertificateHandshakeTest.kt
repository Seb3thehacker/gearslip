package app.seb3thehacker.gearslip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feed protocol messages through the real runner and TLS engine, including peer rejection. */
class CertificateHandshakeTest {
    private val version = message(1, byteArrayOf(0, 1, 0, 3))

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)).use { it.readBytes() }

    private fun identity() = CertProvider.readIdentity(resource("projection_chain.pem"), resource("projection_key.pk8"))

    private fun message(id: Int, body: ByteArray): ByteArray = Frames.build(
        Frames.CHANNEL_CONTROL, false, byteArrayOf((id shr 8).toByte(), id.toByte()) + body,
    )

    private fun hello(): ByteArray {
        val client = SSLContext.getDefault().createSSLEngine().apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.2")
            beginHandshake()
        }
        val packet = ByteBuffer.allocate(client.session.packetBufferSize)
        client.wrap(ByteBuffer.allocate(0), packet)
        return message(3, packet.array().copyOf(packet.position()))
    }

    private fun run(input: ByteArray, mode: CertificateMode = CertificateMode.AUTOMATIC): CertificateRetry {
        val retry = CertificateRetry()
        val attempt = retry.begin("car", mode)
        GearslipRunner(
            ByteArrayInputStream(input), ByteArrayOutputStream(), ::identity,
            certificateForced = attempt.forced,
            onCertificateFailure = { retry.failed(attempt) },
        ).run()
        return retry
    }

    @Test fun `certificate and authentication rejection queue one fallback`() {
        for (status in listOf(-2, -3)) {
            val retry = run(version + hello() + message(4, Protobuf.varintField(1, status.toLong())))
            assertEquals(CertProvider.Source.HEAD_UNIT, retry.begin("car", CertificateMode.AUTOMATIC).source)
            assertEquals("android_auto", SessionReport.sessionNote().getString("cert"))
            assertTrue(SessionStatus.state.value.headline.contains("Reconnect"))
        }
    }

    @Test fun `a TLS certificate alert queues fallback`() {
        // TLS alert record: fatal, bad_certificate. The server has already processed ClientHello.
        val alert = byteArrayOf(21, 3, 3, 0, 2, 2, 42)
        val retry = run(version + hello() + message(3, alert))
        assertEquals(SessionReport.Category.TLS, SessionReport.category)
        assertEquals(CertProvider.Source.HEAD_UNIT, retry.begin("car", CertificateMode.AUTOMATIC).source)
    }

    @Test fun `a silent disconnect during authentication queues fallback`() {
        val retry = run(version + hello())
        assertEquals(CertProvider.Source.HEAD_UNIT, retry.begin("car", CertificateMode.AUTOMATIC).source)
    }

    @Test fun `a failure before the certificate attempt does not queue fallback or claim a certificate`() {
        for (input in listOf(byteArrayOf(), version)) {
            val retry = run(input)
            assertEquals(CertProvider.Source.ANDROID_AUTO, retry.begin("car", CertificateMode.AUTOMATIC).source)
            assertEquals("", SessionReport.sessionNote().getString("cert"))
        }
    }

    @Test fun `a forced phone certificate reports its override and does not retry`() {
        val retry = run(version + hello() + message(4, Protobuf.varintField(1, -2)), CertificateMode.ANDROID_AUTO)
        assertTrue(SessionReport.sessionNote().getBoolean("cert_forced"))
        assertFalse(SessionStatus.state.value.headline.contains("Reconnect"))
        assertEquals(CertProvider.Source.ANDROID_AUTO, retry.begin("car", CertificateMode.AUTOMATIC).source)
    }

    @Test fun `an unreadable identity does not schedule a certificate retry`() {
        var offered = false
        GearslipRunner(
            ByteArrayInputStream(version + hello()), ByteArrayOutputStream(),
            identityProvider = { error("Unreadable identity") },
            onCertificateFailure = { offered = true; true },
        ).run()
        assertFalse(offered)
        assertEquals("", SessionReport.sessionNote().getString("cert"))
    }
}
