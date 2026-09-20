package app.seb3thehacker.gearslip.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How a notification can be answered: the action the app attached, and the field it fills. */
class ReplyTarget(val action: Notification.Action, val input: android.app.RemoteInput)

/**
 * One notification as the car shows it. Only what the car UI needs is kept - the phone's
 * Notification object stays with the listener, apart from the reply action.
 */
data class CarNotification(
    /** Stable per conversation: an app re-posting an update reuses the key. */
    val key: String,
    /** Unique per post, so an update to an already-shown notification still counts as new. */
    val id: Long,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val icon: Bitmap?,
    /** Null when the app offered no reply, or once the notification has left the phone. */
    val reply: ReplyTarget?,
    val replied: Boolean = false,
)

/**
 * The car's notification centre: a short history, the one popup on screen, and an unread count.
 *
 * Held in memory only. Message contents are never written to disk, so a process restart clears
 * the history - which is the safer failure for something shown on a car screen.
 */
object CarNotifications {

    private const val MAX_HISTORY = 50

    /** A reply's own echo (apps re-post the thread with "You: ...") shouldn't pop up again. */
    private const val ECHO_WINDOW_MS = 10_000L

    private val _history = MutableStateFlow<List<CarNotification>>(emptyList())
    val history: StateFlow<List<CarNotification>> = _history.asStateFlow()

    private val _popup = MutableStateFlow<CarNotification?>(null)
    val popup: StateFlow<CarNotification?> = _popup.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    /** True while the system has bound the listener - i.e. the user has granted access. */
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private var nextId = 0L
    private var lastReplyKey: String? = null
    private var lastReplyAt = 0L

    fun setListening(on: Boolean) { _listening.value = on }

    fun nextId(): Long = nextId++

    /** [quiet] adds to the history without a popup or unread badge (used to seed on connect). */
    fun post(notification: CarNotification, quiet: Boolean) {
        _history.update { list ->
            (listOf(notification) + list.filterNot { it.key == notification.key }).take(MAX_HISTORY)
        }
        val echo = notification.key == lastReplyKey &&
            System.currentTimeMillis() - lastReplyAt < ECHO_WINDOW_MS
        if (quiet || echo) return
        // History still keeps it; only the popup is held back while driving.
        if (CarMotion.moving) return
        _popup.value = notification
    }

    /** The phone dismissed it. It stays in history, but its reply intent is no longer trustworthy. */
    fun gone(key: String) {
        _history.update { list -> list.map { if (it.key == key) it.copy(reply = null) else it } }
    }

    fun dismissPopup(id: Long) {
        _popup.update { if (it?.id == id) null else it }
    }

    /** Called while the notification screen is showing: nothing is unread there. */
    fun markRead() {
        _unread.value = 0
        _popup.value = null
    }

    fun clear() {
        _history.value = emptyList()
        _popup.value = null
        _unread.value = 0
    }

    /** Fills the app's reply field and fires its action. Returns false if the app has withdrawn it. */
    fun reply(context: Context, notification: CarNotification, text: String): Boolean {
        val target = notification.reply ?: return false
        val fill = Intent()
        val results = Bundle().apply { putCharSequence(target.input.resultKey, text) }
        android.app.RemoteInput.addResultsToIntent(arrayOf(target.input), fill, results)
        return try {
            target.action.actionIntent.send(context, 0, fill)
            lastReplyKey = notification.key
            lastReplyAt = System.currentTimeMillis()
            _history.update { list ->
                list.map { if (it.key == notification.key) it.copy(replied = true) else it }
            }
            true
        } catch (e: PendingIntent.CanceledException) {
            GearslipLog.w("notify: the reply to ${notification.appLabel} was withdrawn by the app")
            false
        }
    }
}
