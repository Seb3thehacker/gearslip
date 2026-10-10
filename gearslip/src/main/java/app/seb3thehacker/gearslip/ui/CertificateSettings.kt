package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.CertProvider
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keep the choice explicit: expiry and connection failures never change it. */
@Composable
internal fun CertificateSettings(onChange: () -> Unit) {
    val context = LocalContext.current
    var source by remember { mutableStateOf(AppSettings.certificateSource(context)) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val expiry by produceState("Checking expiry…", context, source) {
        value = "Checking expiry…"
        value = withContext(Dispatchers.IO) {
            runCatching {
                val date = CertProvider.loadBundled(context, source).certificate.notAfter
                val formatted = DateFormat.getDateInstance(DateFormat.LONG).format(date)
                if (Date().after(date)) "Expired on $formatted" else "Expires on $formatted"
            }.getOrDefault("Expiry unavailable")
        }
    }
    SettingsRow(
        "Bundled fallback",
        Modifier.clickable(role = Role.Button) { choosing = true },
        subtitle = "${source.label}\n$expiry",
        icon = Icons.Filled.Lock,
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    if (choosing) {
        CertificatePicker(
            selected = source,
            onSelect = {
                AppSettings.setCertificateSource(context, it)
                source = it
                choosing = false
                onChange()
            },
            onDismiss = { choosing = false },
        )
    }
}

@Composable
private fun CertificatePicker(
    selected: CertProvider.Source,
    onSelect: (CertProvider.Source) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose bundled fallback") },
        text = {
            // Scrolling keeps both choices reachable on small screens and at larger font sizes.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Used when no imported, downloaded, or staged certificate is loaded. Changes apply on your next connection.", style = MaterialTheme.typography.bodyMedium)
                Column(
                    Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CertProvider.Source.entries.forEach { option ->
                        CertificateOption(option, selected == option, onClick = { onSelect(option) })
                    }
                }
                Text(
                    "Your choice is saved until you change it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The whole padded row owns selection, giving the radio and its description one touch target. */
@Composable
private fun CertificateOption(source: CertProvider.Source, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .heightIn(min = 80.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    source.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                )
                Text(
                    when (source) {
                        CertProvider.Source.ANDROID_AUTO -> "Default. Tried even after expiry."
                        CertProvider.Source.HEAD_UNIT -> "Try this if Android Auto fails."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** Recheck on resume, and remember acknowledgement per certificate rather than per app install. */
@Composable
internal fun CompatWarning(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var resume by remember { mutableIntStateOf(0) }
    var expiry by remember { mutableStateOf<Date?>(null) }
    var warningKey by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resume++ }
    LaunchedEffect(resume) {
        if (AppSettings.certificateSource(context) != CertProvider.Source.ANDROID_AUTO) {
            expiry = null
            return@LaunchedEffect
        }
        val cert = withContext(Dispatchers.IO) {
            runCatching { CertProvider.load(context).takeIf { it.kind == CertProvider.Kind.BUNDLED }?.certificate }.getOrNull()
        }
        expiry = cert?.notAfter?.takeIf { Date().after(it) }
        // The old warning promised automatic fallback, so acknowledge this wording separately.
        warningKey = cert?.let { "manual:${it.serialNumber.toString(16)}:${it.notAfter.time}" }.orEmpty()
        acknowledged = AppSettings.getString(context, "expired_certificate_warning", "")
    }
    val expiredOn = expiry ?: return
    if (warningKey == acknowledged) return
    fun dismiss() {
        AppSettings.putString(context, "expired_certificate_warning", warningKey)
        acknowledged = warningKey
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("Android Auto certificate expired") },
        text = {
            Text(
                "The Android Auto certificate expired on ${DateFormat.getDateInstance(DateFormat.LONG).format(expiredOn)}. " +
                    "Your car may still accept it, so Gearslip will keep using it. If the connection fails, " +
                    "open Settings, select Bundled fallback → Head unit (DHU), and reconnect. " +
                    "The head-unit certificate may not work with every car.",
            )
        },
        confirmButton = { TextButton(onClick = ::dismiss) { Text("Got it") } },
        dismissButton = {
            TextButton(onClick = { dismiss(); onOpenSettings() }) { Text("Settings") }
        },
    )
}
