package app.seb3thehacker.gearslip.car

import androidx.annotation.MainThread
import androidx.car.app.CarToast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One toast across navigation and browse apps; newer requests replace stale messages. */
@MainThread
internal object CarToasts {
    // Identity matters: repeating identical text must restart its display timer.
    class Message(val owner: Any, val text: String, val durationMs: Long)

    private val current = MutableStateFlow<Message?>(null)
    val message: StateFlow<Message?> = current.asStateFlow()

    fun show(owner: Any, text: String, duration: Int) {
        if (text.isBlank()) return
        // Longer than a phone toast: the driver only glances at the screen now and then.
        val durationMs = if (duration == CarToast.LENGTH_LONG) 7_000L else 4_000L
        current.value = Message(owner, text, durationMs)
    }

    fun dismiss(message: Message) {
        // An old timer must not dismiss a newer toast, even if its text is identical.
        if (current.value === message) current.value = null
    }

    fun clear(owner: Any) {
        // Disconnecting the map must not clear a toast belonging to the browse app.
        if (current.value?.owner === owner) current.value = null
    }
}
