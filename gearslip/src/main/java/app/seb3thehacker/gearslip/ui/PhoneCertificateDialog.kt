package app.seb3thehacker.gearslip.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.CertProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The same two-file import is available during setup and in Settings. */
@Composable
fun PhoneCertificateDialog(onDismiss: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var certificate by remember { mutableStateOf<Uri?>(null) }
    var privateKey by remember { mutableStateOf<Uri?>(null) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val certificatePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) { certificate = it; error = null }
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) { privateKey = it; error = null }
    }

    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text("Import phone certificate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose a PEM certificate chain, with the phone certificate first, and its matching RSA private key.")
                OutlinedButton(enabled = !importing, onClick = { certificatePicker.launch(arrayOf("*/*")) }) {
                    Text(if (certificate == null) "Choose certificate chain" else "Change certificate chain")
                }
                OutlinedButton(enabled = !importing, onClick = { keyPicker.launch(arrayOf("*/*")) }) {
                    Text(if (privateKey == null) "Choose private key" else "Change private key")
                }
                Text("Use an unencrypted PEM key or a binary PKCS#8 (.pk8) key. To use a password, import a .p12 file instead.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = certificate != null && privateKey != null && !importing,
                onClick = {
                    val certUri = certificate ?: return@TextButton
                    val keyUri = privateKey ?: return@TextButton
                    importing = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { CertProvider.importPem(context, certUri, keyUri) }
                        }
                        importing = false
                        result.onSuccess { onImported() }.onFailure {
                            error = "Could not import: ${it.message ?: "check the certificate and private key files"}"
                        }
                    }
                },
            ) { Text(if (importing) "Importing…" else "Import") }
        },
        dismissButton = {
            TextButton(enabled = !importing, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
