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
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.CertificateMode
import app.seb3thehacker.gearslip.CertProvider
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Release builds retain one informational row; only debug builds can force an identity. */
@Composable
internal fun CertificateSettings() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AppSettings.certificateMode(context)) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val expiry by produceState<Date?>(null, context, mode) {
        value = withContext(Dispatchers.IO) {
            runCatching { CertProvider.load(context, mode.source ?: CertProvider.Source.ANDROID_AUTO).certificate.notAfter }.getOrNull()
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
    if (!BuildConfig.DEBUG) return
    SettingsDivider()
    SettingsRow(
        "Certificate for debugging",
        Modifier.clickable { choosing = true },
        subtitle = "${mode.label} · Applies on the next connection",
        icon = Icons.Filled.Lock,
    )
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Certificate for debugging") },
            text = {
                Column {
                    Text("Automatic tries Android Auto first. Forcing a certificate disables fallback, even after a failure.")
                    CertificateMode.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = mode == option,
                                role = Role.RadioButton,
                                onClick = {
                                    AppSettings.setCertificateMode(context, option)
                                    mode = option
                                    choosing = false
                                },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == option, onClick = null)
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
internal fun CompatWarning() {
    val context = LocalContext.current
    var resume by remember { mutableIntStateOf(0) }
    var expiry by remember { mutableStateOf<Date?>(null) }
    var warningKey by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resume++ }
    LaunchedEffect(resume) {
        val cert = withContext(Dispatchers.IO) { runCatching { CertProvider.load(context).certificate }.getOrNull() }
        expiry = cert?.notAfter?.takeIf { Date().after(it) }
        warningKey = cert?.let { "${it.serialNumber.toString(16)}:${it.notAfter.time}" }.orEmpty()
        acknowledged = AppSettings.getString(context, "expired_certificate_warning", "")
    }
    val expiredOn = expiry ?: return
    if (warningKey == acknowledged) return
    val mode = AppSettings.certificateMode(context)
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
                    "Your car may still accept it, so Gearslip tries it first. If the secure connection fails, " +
                    "Gearslip will try the head-unit certificate on the next connection. You may need to reconnect. " +
                    "The fallback may not work with every car." +
                    if (mode != CertificateMode.AUTOMATIC) "\n\nYour debug override forces ${mode.label} and disables fallback." else "",
            )
        },
        confirmButton = { TextButton(onClick = ::dismiss) { Text("Got it") } },
    )
}
