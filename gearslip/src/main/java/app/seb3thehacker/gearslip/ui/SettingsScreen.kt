package app.seb3thehacker.gearslip.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.CertProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            StartupSection()
            CertificateSection()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun StartupSection() {
    val context = LocalContext.current
    var url by remember { mutableStateOf(AppSettings.startupUrl(context)) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Startup URL")
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                AppSettings.setStartupUrl(context, it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("URL") },
            placeholder = { Text("https://example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = {
                Text("Shown on the car screen. Leave empty for the built-in test page. Applies on the next connection.")
            },
        )
    }
}

@Composable
private fun CertificateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    val summary by produceState<CertSummary?>(null, refresh) {
        value = withContext(Dispatchers.Default) { CertSummary.read(context) }
    }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingUri = uri
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Certificate")

        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = summary
                if (s == null) {
                    Text("Checking…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(s.headline, style = MaterialTheme.typography.bodyLarge)
                    s.subject?.let { Detail("Subject", it) }
                    s.issuer?.let { Detail("Issuer", it) }
                    s.validUntil?.let { Detail("Valid until", it) }
                }
            }
        }

        Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Import certificate (.p12)")
        }
        if (summary?.kind == CertProvider.Kind.IMPORTED) {
            OutlinedButton(
                onClick = {
                    CertProvider.removeImported(context)
                    notice = "Removed the imported certificate."
                    refresh++
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Remove imported certificate") }
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            "An imported certificate takes priority over one staged over adb. Takes effect on the next connection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    pendingUri?.let { uri ->
        PasswordDialog(
            onDismiss = { pendingUri = null },
            onConfirm = { password, reportError ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { CertProvider.importFrom(context, uri, password) }
                    }
                    result.onSuccess {
                        pendingUri = null
                        notice = "Certificate imported."
                        refresh++
                    }.onFailure {
                        reportError("Could not import: wrong password, or not a PKCS#12 file with a private key.")
                    }
                }
            },
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String, (String) -> Unit) -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Certificate password") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                singleLine = true,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = error != null,
                supportingText = { Text(error ?: "Leave empty if the file has none.") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) { error = it } }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
