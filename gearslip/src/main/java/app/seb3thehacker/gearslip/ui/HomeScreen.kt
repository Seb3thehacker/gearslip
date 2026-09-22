package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.SessionStatus

@Composable
fun HomeScreen(
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCarPreview: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val status by SessionStatus.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var debug by remember { mutableStateOf(AppSettings.debugMode(context)) }

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
                Spacer(Modifier.height(24.dp))
                SetupSteps()
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Debug mode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (debug) "Full protocol logging, live logs and car preview."
                            else "Quiet logging. Warnings and errors are still recorded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = debug, onCheckedChange = {
                        debug = it
                        AppSettings.setDebugMode(context, it)
                    })
                }
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
                if (!live && debug) {
                    FilledTonalButton(
                        onClick = onOpenCarPreview,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text("Car preview")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (debug) {
                        FilledTonalButton(onClick = onOpenLogs, modifier = Modifier.weight(1f).height(56.dp)) {
                            Text("Live logs")
                        }
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

/**
 * One step of the setup list: a sentence that says what to do, and optionally how.
 *
 * [command] is for the one step that cannot be done on the phone. It is shown as something to
 * copy rather than to read, because a mistyped package name fails silently.
 */
private class Step(val action: String, val detail: String? = null, val command: String? = null)

private val SETUP_STEPS = listOf(
    Step("Use a car from 2020 or earlier.", "Newer head units reject the certificate."),
    Step(
        "On GrapheneOS, exempt Gearslip from exploit protections.",
        "Open Settings, then Apps, then Gearslip, and turn on Exploit protection compatibility mode.",
    ),
    Step("Disable or uninstall the Android Auto app."),
    Step("Load the certificate.", "Open Settings and choose Import certificate."),
    Step(
        "Allow the microphone, so music can reach the car.",
        "Android asks for the microphone before it will let any app pass music along. " +
            "Gearslip never listens to you: it copies what a music app is playing, and nothing else.",
    ),
    Step(
        "Optional: stop Android asking about audio every drive.",
        "Connect the phone to a computer with USB debugging turned on, and run this command. " +
            "It tells Android to trust Gearslip with audio from now on. Skip it and everything " +
            "still works, but you have to tap Start once each time you set off.",
        command = "adb shell appops set app.seb3thehacker.gearslip PROJECT_MEDIA allow",
    ),
    Step(
        "Optional: keep notifications readable while music plays.",
        "Android treats sending audio to the car like sharing your screen, and hides what your " +
            "notifications say. Run this command too, and Gearslip switches that off only while " +
            "music is going to the car, then puts it back.",
        command = "adb shell pm grant app.seb3thehacker.gearslip android.permission.WRITE_SECURE_SETTINGS",
    ),
    Step("Plug the phone into the car.", "Gearslip opens by itself when the head unit connects."),
)

/**
 * A command to run on a computer, shown so it can be copied rather than retyped. Tapping it puts
 * it on the clipboard, which is the only way it reaches the computer from here anyway.
 */
@Composable
private fun CopyableCommand(command: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        onClick = {
            clipboard.setText(AnnotatedString(command))
            Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Tap to copy",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** What to do before the first drive, in order. The same width as the status card above it. */
@Composable
private fun SetupSteps() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    "How to connect",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SETUP_STEPS.forEachIndexed { index, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(16.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(step.action, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            step.detail?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            step.command?.let { CopyableCommand(it) }
                        }
                    }
                }
            }
        }
    }
}
