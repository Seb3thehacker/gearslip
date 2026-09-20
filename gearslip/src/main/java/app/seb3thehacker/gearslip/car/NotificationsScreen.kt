package app.seb3thehacker.gearslip.car

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.notify.CarNotification
import app.seb3thehacker.gearslip.notify.CarNotifications
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** The notification history, with a reply pane for anything the sending app lets us answer. */
@Composable
fun NotificationsScreen(replyTo: String?) {
    val history by CarNotifications.history.collectAsState()
    var replying by remember(replyTo) { mutableStateOf(replyTo) }

    // Anything arriving while this is open is being looked at, so it never counts as unread.
    LaunchedEffect(history) { CarNotifications.markRead() }

    val target = history.firstOrNull { it.key == replying && it.reply != null }
    if (target != null) {
        ReplyPane(target, onDone = { replying = null })
    } else {
        NotificationList(history, onReply = { replying = it.key })
    }
}

@Composable
private fun NotificationList(history: List<CarNotification>, onReply: (CarNotification) -> Unit) {
    val listening by CarNotifications.listening.collectAsState()
    val context = LocalContext.current.applicationContext

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Notifications",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (history.isNotEmpty()) TextButton(onClick = CarNotifications::clear) { Text("Clear all") }
        }

        if (!listening) {
            AccessBanner {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        if (history.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing yet. New notifications from the phone will show up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history, key = { it.key }) { NotificationRow(it, onReply) }
            }
        }
    }
}

@Composable
private fun AccessBanner(onOpen: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Text(
                "Notifications are off. Allow Gearslip under Settings > Notifications > Notification access on the phone.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onOpen) { Text("Open") }
        }
    }
}

@Composable
private fun NotificationRow(n: CarNotification, onReply: (CarNotification) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NotificationIcon(n)
            Column(Modifier.weight(1f)) {
                Text(
                    "${n.appLabel}  ·  ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(n.postedAt))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (n.title.isNotBlank()) {
                    Text(n.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (n.text.isNotBlank()) {
                    Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                n.replied -> Icon(Icons.Filled.Check, contentDescription = "Replied", tint = MaterialTheme.colorScheme.primary)
                n.reply != null -> FilledTonalButton(onClick = { onReply(n) }) { Text("Reply") }
            }
        }
    }
}

@Composable
private fun NotificationIcon(n: CarNotification, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val icon = n.icon
    if (icon != null) {
        Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)))
    } else {
        Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(size))
    }
}

/** Same shape as search: a field on top, the host's own keyboard below. */
@Composable
private fun ReplyPane(n: CarNotification, onDone: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var text by remember(n.key) { mutableStateOf("") }

    fun send() {
        if (text.isBlank()) return
        CarNotifications.reply(context, n, text.trim())
        onDone()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (text.isEmpty()) "Reply to ${n.title.ifBlank { n.appLabel }}" else "$text|",
                    color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // What is being answered, so the driver isn't replying blind.
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NotificationIcon(n)
            Text(n.text.ifBlank { n.title }, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }

        CarKeyboard(text, onTextChange = { text = it }, onSubmit = ::send, submitImage = Icons.AutoMirrored.Filled.Send)
    }
}

/**
 * The popup shown over whatever the car is displaying. It times itself out, and tapping it opens
 * the history - or straight into a reply, when the app allows one.
 */
@Composable
fun NotificationPopup(modifier: Modifier = Modifier) {
    val popup by CarNotifications.popup.collectAsState()
    val navigator = LocalCarNavigator.current
    val n = popup ?: return

    LaunchedEffect(n.id) {
        delay(POPUP_MS)
        CarNotifications.dismissPopup(n.id)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
        modifier = modifier
            .padding(top = 8.dp)
            .fillMaxWidth(0.7f)
            .clickable { navigator.notifications() },
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NotificationIcon(n)
            Column(Modifier.weight(1f)) {
                Text(n.appLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                if (n.title.isNotBlank()) {
                    Text(n.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (n.text.isNotBlank()) {
                    Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (n.reply != null) {
                FilledTonalButton(onClick = { navigator.notifications(replyTo = n.key) }) { Text("Reply") }
            }
            IconButton(onClick = { CarNotifications.dismissPopup(n.id) }) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

private const val POPUP_MS = 8_000L
