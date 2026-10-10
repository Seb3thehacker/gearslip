package app.seb3thehacker.gearslip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateRetryTest {
    @Test fun `a failed phone identity gets one fallback before returning to the phone identity`() {
        val retry = CertificateRetry()
        val primary = retry.begin("car", CertificateMode.AUTOMATIC)
        assertEquals(CertProvider.Source.ANDROID_AUTO, primary.source)
        assertTrue(retry.failed(primary))
        retry.closed(primary)
        val fallback = retry.begin("car", CertificateMode.AUTOMATIC)
        assertEquals(CertProvider.Source.HEAD_UNIT, fallback.source)
        assertFalse(retry.failed(fallback))
        assertEquals(CertProvider.Source.ANDROID_AUTO, retry.begin("car", CertificateMode.AUTOMATIC).source)
    }

    @Test fun `an accepted phone identity never changes the next attempt`() {
        val retry = CertificateRetry()
        retry.closed(retry.begin("car", CertificateMode.AUTOMATIC))
        assertEquals(CertProvider.Source.ANDROID_AUTO, retry.begin("car", CertificateMode.AUTOMATIC).source)
    }

    @Test fun `a different accessory does not inherit a pending fallback`() {
        val retry = CertificateRetry()
        retry.failed(retry.begin("car A", CertificateMode.AUTOMATIC))
        assertEquals(CertProvider.Source.ANDROID_AUTO, retry.begin("car B", CertificateMode.AUTOMATIC).source)
        assertEquals(CertProvider.Source.ANDROID_AUTO, retry.begin("car A", CertificateMode.AUTOMATIC).source)
    }

    @Test fun `a later drive does not inherit a pending fallback`() {
        var time = 0L
        val retry = CertificateRetry { time }
        retry.failed(retry.begin("car", CertificateMode.AUTOMATIC))
        time = 5L * 60 * 1_000_000_000
        assertEquals(CertProvider.Source.ANDROID_AUTO, retry.begin("car", CertificateMode.AUTOMATIC).source)
    }

    @Test fun `forced identities bypass pending retries and never schedule fallback`() {
        for (mode in listOf(CertificateMode.ANDROID_AUTO, CertificateMode.HEAD_UNIT)) {
            val retry = CertificateRetry()
            retry.failed(retry.begin("car", CertificateMode.AUTOMATIC))
            val forced = retry.begin("car", mode)
            assertEquals(mode.source, forced.source)
            assertTrue(forced.forced)
            assertFalse(retry.failed(forced))
            assertEquals(CertProvider.Source.ANDROID_AUTO, retry.begin("car", CertificateMode.AUTOMATIC).source)
        }
    }

    @Test fun `late errors from a closed or superseded session cannot queue a fallback`() {
        val retry = CertificateRetry()
        val closed = retry.begin("car", CertificateMode.AUTOMATIC)
        retry.closed(closed)
        assertFalse(retry.failed(closed))
        val old = retry.begin("car", CertificateMode.AUTOMATIC)
        retry.begin("car", CertificateMode.AUTOMATIC)
        assertFalse(retry.failed(old))
    }

    @Test fun `duplicate errors cannot renew a pending retry`() {
        var time = 0L
        val retry = CertificateRetry { time }
        val attempt = retry.begin("car", CertificateMode.AUTOMATIC)
        assertTrue(retry.failed(attempt))
        time = 4L * 60 * 1_000_000_000
        assertFalse(retry.failed(attempt))
        time = 5L * 60 * 1_000_000_000
        assertEquals(CertProvider.Source.ANDROID_AUTO, retry.begin("car", CertificateMode.AUTOMATIC).source)
    }

    @Test fun `changing debug mode clears retry and invalidates an active callback`() {
        val retry = CertificateRetry()
        val attempt = retry.begin("car", CertificateMode.AUTOMATIC)
        retry.failed(attempt)
        retry.clear()
        assertFalse(retry.failed(attempt))
        assertEquals(CertProvider.Source.ANDROID_AUTO, retry.begin("car", CertificateMode.AUTOMATIC).source)
    }
}
