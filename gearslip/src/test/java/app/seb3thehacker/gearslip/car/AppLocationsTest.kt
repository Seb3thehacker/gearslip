package app.seb3thehacker.gearslip.car

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AppLocationsTest {
    private val owner = Any()
    private val now = 1_000_000_000_000L
    private val fix = AppLocation(41.4, 2.2, 8f, now)

    @Before fun begin() = AppLocations.begin(owner)
    @After fun clear() = AppLocations.clear(owner)

    @Test fun `fresh fixes remain available until they expire`() {
        AppLocations.update(owner, fix, now)
        assertEquals(fix, AppLocations.latest(now + 120_000_000_000L))
        assertNull(AppLocations.latest(now + 120_000_000_001L))
    }

    @Test fun `invalid coordinates accuracy and timestamps are ignored`() {
        listOf(
            fix.copy(latitude = Double.NaN), fix.copy(latitude = 91.0),
            fix.copy(longitude = Double.POSITIVE_INFINITY), fix.copy(longitude = -181.0),
            fix.copy(accuracyMeters = -1f), fix.copy(accuracyMeters = Float.NaN),
            fix.copy(elapsedRealtimeNanos = 0), fix.copy(elapsedRealtimeNanos = now + 1),
            fix.copy(elapsedRealtimeNanos = now - 120_000_000_001L),
        ).forEach { invalid ->
            AppLocations.update(owner, invalid, now)
            assertNull(AppLocations.latest(now))
        }
    }

    @Test fun `zero coordinates are valid`() {
        val equator = fix.copy(latitude = 0.0, longitude = 0.0)
        AppLocations.update(owner, equator, now)
        assertEquals(equator, AppLocations.latest(now))
    }

    @Test fun `out of order and duplicate fixes do not replace the latest`() {
        AppLocations.update(owner, fix, now)
        AppLocations.update(owner, fix.copy(latitude = 42.0, elapsedRealtimeNanos = now - 1), now)
        AppLocations.update(owner, fix.copy(latitude = 42.0), now)
        assertEquals(fix, AppLocations.latest(now))
    }

    @Test fun `replaced owners cannot update or clear the new session`() {
        val previous = Any()
        AppLocations.begin(previous)
        AppLocations.update(previous, fix, now)
        AppLocations.begin(owner)
        assertNull(AppLocations.latest(now))
        AppLocations.update(previous, fix, now)
        assertNull(AppLocations.latest(now))
        AppLocations.update(owner, fix, now)
        AppLocations.clear(previous)
        assertEquals(fix, AppLocations.latest(now))
    }

    @Test fun `disconnect clears the fix and rejects queued updates`() {
        AppLocations.update(owner, fix, now)
        AppLocations.clear(owner)
        AppLocations.update(owner, fix, now)
        assertNull(AppLocations.latest(now))
    }
}
