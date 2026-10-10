package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
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
internal fun CertificateSettings() {
    val context = LocalContext.current
    var source by remember { mutableStateOf(AppSettings.certificateSource(context)) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val expiry by produceState<Date?>(null, context, source) {
        value = withContext(Dispatchers.IO) {
            runCatching { CertProvider.load(context, source).certificate.notAfter }.getOrNull()
        }
    }
    SettingsRow(
        "Certificate expiry",
        subtitle = expiry?.let {
            val date = DateFormat.getDateInstance(DateFormat.LONG).format(it)
            if (Date().after(it)) "Expired on $date" else date
        } ?: "Unavailable",
        icon = Icons.Filled.Lock,
    )
    SettingsDivider()
    SettingsRow(
        "Certificate",
        Modifier.clickable { choosing = true },
        subtitle = "${source.label} · Applies on the next connection",
        icon = Icons.Filled.Lock,
    )
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Certificate") },
            text = {
                Column {
                    Text("Android Auto is the default, even after expiry. If it fails, select Head unit (DHU) and reconnect. Gearslip keeps your choice until you change it.")
                    CertProvider.Source.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = source == option,
                                role = Role.RadioButton,
                                onClick = {
                                    AppSettings.setCertificateSource(context, option)
                                    source = option
                                    choosing = false
                                },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = source == option, onClick = null)
                            Text(option.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
        )
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
        val cert = withContext(Dispatchers.IO) { runCatching { CertProvider.load(context).certificate }.getOrNull() }
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
                    "open Settings, select Certificate → Head unit (DHU), and reconnect. " +
                    "The head-unit certificate may not work with every car.",
            )
        },
        confirmButton = { TextButton(onClick = ::dismiss) { Text("Got it") } },
        dismissButton = {
            TextButton(onClick = { dismiss(); onOpenSettings() }) { Text("Settings") }
        },
    )
}
