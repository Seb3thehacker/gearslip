package app.seb3thehacker.gearslip.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap

/**
 * Feeds the phone's notifications to the car.
 *
 * Reading other apps' notifications is gated by the user granting "Notification access" to
 * Gearslip in the phone's settings - the same on-device consent as any notification mirror,
 * and the only one needed. Until then the car UI says so and offers to open that page.
 */
class GearslipNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        CarNotifications.setListening(true)
        // Seed the history with what is already showing, oldest first so the newest ends up on top.
        runCatching { activeNotifications.orEmpty().sortedBy { it.postTime } }
            .getOrDefault(emptyList())
            .forEach { ingest(it, quiet = true) }
    }

    override fun onListenerDisconnected() = CarNotifications.setListening(false)

    override fun onNotificationPosted(sbn: StatusBarNotification) = ingest(sbn, quiet = false)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = CarNotifications.gone(sbn.key)

    private fun ingest(sbn: StatusBarNotification, quiet: Boolean) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        // Ongoing entries (media, navigation, downloads) and group headers are status, not news.
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category in QUIET_CATEGORIES) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        CarNotifications.post(
            CarNotification(
                key = sbn.key,
                id = CarNotifications.nextId(),
                appLabel = appLabel(sbn.packageName),
                title = title,
                text = text,
                postedAt = sbn.postTime,
                icon = icon(sbn),
                reply = replyOf(n),
            ),
            quiet,
        )
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName.substringAfterLast('.'))

    private fun icon(sbn: StatusBarNotification) = runCatching {
        val drawable = sbn.notification.getLargeIcon()?.loadDrawable(this)
            ?: packageManager.getApplicationIcon(sbn.packageName)
        drawable.toBitmap(ICON_PX, ICON_PX)
    }.getOrNull()

    /** Messaging apps often attach the reply only for wearables, so both places are searched. */
    private fun replyOf(n: Notification): ReplyTarget? {
        val actions = n.actions.orEmpty().toList() + Notification.WearableExtender(n).actions
        for (action in actions) {
            if (action.actionIntent == null) continue
            val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: continue
            return ReplyTarget(action, input)
        }
        return null
    }

    private companion object {
        const val ICON_PX = 96
        val QUIET_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM,
        )
    }
}
