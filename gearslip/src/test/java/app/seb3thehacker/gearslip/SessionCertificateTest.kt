package app.seb3thehacker.gearslip

import java.time.Instant
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCertificateTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)).use { it.readBytes() }

    @Test fun `an expired phone certificate can still be loaded and reported as the attempted identity`() {
        val identity = CertProvider.readIdentity(resource("projection_chain.pem"), resource("projection_key.pk8"))
        SessionReport.begin()
        SessionReport.certificate(identity, false, Date.from(Instant.parse("2030-01-01T00:00:00Z")))
        val note = SessionReport.sessionNote()
        assertEquals("android_auto", note.getString("cert"))
        assertTrue(note.getBoolean("cert_expired"))
        assertFalse(note.getBoolean("cert_forced"))
    }

    @Test fun `a forced head unit identity is distinguished in stats and logs`() {
        val identity = CertProvider.readIdentity(
            resource("projection_fallback.pem"), resource("projection_fallback_key.pk8"),
            CertProvider.Source.HEAD_UNIT.description, CertProvider.Source.HEAD_UNIT.statsKey,
        )
        SessionReport.begin()
        SessionReport.certificate(identity, true, Date.from(Instant.parse("2030-01-01T00:00:00Z")))
        val note = SessionReport.sessionNote()
        assertEquals("dhu", note.getString("cert"))
        assertFalse(note.getBoolean("cert_expired"))
        assertTrue(note.getBoolean("cert_forced"))
        assertTrue(SessionReport.render().contains(CertProvider.Source.HEAD_UNIT.description))
        SessionReport.begin()
        assertEquals("", SessionReport.sessionNote().getString("cert"))
        assertFalse(SessionReport.sessionNote().getBoolean("cert_forced"))
    }
}
