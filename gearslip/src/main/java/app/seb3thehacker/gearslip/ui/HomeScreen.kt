package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.SessionStatus

@Composable
fun HomeScreen(
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCarPreview: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val status by SessionStatus.state.collectAsStateWithLifecycle()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Gearslip",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "v${LocalContext.current.appVersionName()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                StatusCard(status)
                Spacer(Modifier.height(16.dp))
            }

            // Actions sit at the bottom, where a thumb reaches them.
            Column(
                Modifier.padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val live = status.phase == SessionStatus.Phase.CONNECTING ||
                    status.phase == SessionStatus.Phase.PROJECTING
                if (live) {
                    Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("Disconnect")
                    }
                }
                if (!live) {
                    FilledTonalButton(
                        onClick = onOpenCarPreview,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text("Car preview")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onOpenLogs, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("Live logs")
                    }
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("Settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: SessionStatus.Snapshot) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (status.phase) {
        SessionStatus.Phase.PROJECTING -> scheme.primaryContainer to scheme.onPrimaryContainer
        SessionStatus.Phase.CONNECTING -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SessionStatus.Phase.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        SessionStatus.Phase.IDLE, SessionStatus.Phase.DISCONNECTED ->
            scheme.surfaceContainerHigh to scheme.onSurface
    }

    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraLarge) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (status.phase == SessionStatus.Phase.CONNECTING) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = content)
            } else {
                Surface(
                    modifier = Modifier.size(14.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (status.phase == SessionStatus.Phase.IDLE ||
                        status.phase == SessionStatus.Phase.DISCONNECTED
                    ) content.copy(alpha = 0.4f) else content,
                ) {}
            }
            Column {
                Text(status.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(status.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The version shown on screen, straight from the manifest - one place to bump per release. */
private fun Context.appVersionName(): String =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

