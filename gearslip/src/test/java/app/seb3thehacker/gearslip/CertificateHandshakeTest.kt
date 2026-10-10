package app.seb3thehacker.gearslip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Feed protocol messages through the real runner and TLS engine, including peer rejection. */
class CertificateHandshakeTest {
    private val version = message(1, byteArrayOf(0, 1, 0, 3))

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)).use { it.readBytes() }

    private fun identity(source: CertProvider.Source) = when (source) {
        CertProvider.Source.ANDROID_AUTO -> CertProvider.readIdentity(resource("projection_chain.pem"), resource("projection_key.pk8"))
        CertProvider.Source.HEAD_UNIT -> CertProvider.readIdentity(
            resource("projection_fallback.pem"), resource("projection_fallback_key.pk8"), source.description, source.statsKey,
        )
    }

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

    private fun run(input: ByteArray, source: CertProvider.Source = CertProvider.Source.ANDROID_AUTO): Int {
        val loads = java.util.concurrent.atomic.AtomicInteger()
        GearslipRunner(
            ByteArrayInputStream(input), ByteArrayOutputStream(),
            identityProvider = { loads.incrementAndGet(); identity(source) },
        ).run()
        return loads.get()
    }

    @Test fun `certificate and authentication rejection do not retry or change identity`() {
        for (status in listOf(-2, -3)) {
            val loads = run(version + hello() + message(4, Protobuf.varintField(1, status.toLong())))
            assertEquals(1, loads)
            assertEquals("android_auto", SessionReport.sessionNote().getString("cert"))
            assertFalse(SessionStatus.state.value.headline.contains("Reconnect"))
        }
    }

    @Test fun `a TLS certificate alert leaves the selected identity unchanged`() {
        // TLS alert record: fatal, bad_certificate. The server has already processed ClientHello.
        val alert = byteArrayOf(21, 3, 3, 0, 2, 2, 42)
        val loads = run(version + hello() + message(3, alert))
        assertEquals(SessionReport.Category.TLS, SessionReport.category)
        assertEquals(1, loads)
        assertEquals("android_auto", SessionReport.sessionNote().getString("cert"))
    }

    @Test fun `a silent disconnect during authentication leaves the selected identity unchanged`() {
        val loads = run(version + hello())
        assertEquals(1, loads)
        assertEquals("android_auto", SessionReport.sessionNote().getString("cert"))
    }

    @Test fun `a failure before the certificate attempt does not claim a certificate`() {
        for (input in listOf(byteArrayOf(), version)) {
            run(input)
            assertEquals("", SessionReport.sessionNote().getString("cert"))
        }
    }

    @Test fun `a manually selected head unit certificate is reported and is not retried`() {
        val loads = run(version + hello() + message(4, Protobuf.varintField(1, -2)), CertProvider.Source.HEAD_UNIT)
        assertEquals(1, loads)
        assertEquals("dhu", SessionReport.sessionNote().getString("cert"))
        assertFalse(SessionStatus.state.value.headline.contains("Reconnect"))
    }

    @Test fun `an unreadable identity is reported as a local error`() {
        GearslipRunner(
            ByteArrayInputStream(version + hello()), ByteArrayOutputStream(),
            identityProvider = { error("Unreadable identity") },
        ).run()
        assertEquals("Certificate unavailable", SessionStatus.state.value.headline)
        assertEquals("", SessionReport.sessionNote().getString("cert"))
    }
}
