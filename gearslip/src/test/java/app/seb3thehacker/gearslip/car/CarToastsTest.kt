package app.seb3thehacker.gearslip.car

import androidx.car.app.CarToast
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class CarToastsTest {
    @Before
    @After
    fun clearToast() {
        CarToasts.message.value?.let(CarToasts::dismiss)
    }

    @Test
    fun expiredToastCannotDismissItsReplacement() {
        val owner = Any()
        CarToasts.show(owner, "First", CarToast.LENGTH_SHORT)
        val first = requireNotNull(CarToasts.message.value)
        CarToasts.show(owner, "Second", CarToast.LENGTH_LONG)
        val second = requireNotNull(CarToasts.message.value)

        CarToasts.dismiss(first)
        assertSame(second, CarToasts.message.value)
        CarToasts.dismiss(second)
        assertNull(CarToasts.message.value)
    }

    @Test
    fun identicalMessagesStillRestartTheDisplayTimer() {
        val owner = Any()
        CarToasts.show(owner, "Saved", CarToast.LENGTH_SHORT)
        val first = requireNotNull(CarToasts.message.value)
        CarToasts.show(owner, "Saved", CarToast.LENGTH_SHORT)
        val repeated = requireNotNull(CarToasts.message.value)

        assertNotSame(first, repeated)
        CarToasts.dismiss(first)
        assertSame(repeated, CarToasts.message.value)
    }

    @Test
    fun disconnectOnlyClearsItsOwnToast() {
        val nav = Any()
        val browse = Any()
        CarToasts.show(nav, "Route saved", CarToast.LENGTH_SHORT)
        CarToasts.show(browse, "Added to playlist", CarToast.LENGTH_SHORT)
        val latest = CarToasts.message.value

        CarToasts.clear(nav)
        assertSame(latest, CarToasts.message.value)
        CarToasts.clear(browse)
        assertNull(CarToasts.message.value)
    }

    @Test
    fun blankRequestsDoNotReplaceAVisibleMessage() {
        val owner = Any()
        CarToasts.show(owner, "Saved", CarToast.LENGTH_SHORT)
        val visible = CarToasts.message.value

        CarToasts.show(owner, " \n\t", CarToast.LENGTH_LONG)
        assertSame(visible, CarToasts.message.value)
    }

    @Test
    fun unknownDurationsFallBackToShortRatherThanStayingVisible() {
        val owner = Any()
        CarToasts.show(owner, "Short", CarToast.LENGTH_SHORT)
        assertEquals(4_000L, CarToasts.message.value?.durationMs)
        CarToasts.show(owner, "Long", CarToast.LENGTH_LONG)
        assertEquals(7_000L, CarToasts.message.value?.durationMs)
        CarToasts.show(owner, "Unknown", Int.MAX_VALUE)
        assertEquals(4_000L, CarToasts.message.value?.durationMs)
    }
}
