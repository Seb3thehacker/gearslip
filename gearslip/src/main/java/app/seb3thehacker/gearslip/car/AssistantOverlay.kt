package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.car.theme.*
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import app.seb3thehacker.gearslip.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The band that appears while [CarAssistant] is listening, thinking or speaking - a thin strip
 * across the top, same idea as [CallOverlay]'s active-call banner, so it never blocks the screen
 * the driver was already looking at.
 */
@Composable
fun AssistantOverlay(modifier: Modifier = Modifier) {
    val state by CarAssistant.state.collectAsState()
    if (state.phase == CarAssistant.Phase.IDLE) return
    val context = LocalContext.current

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PulsingMic(state.phase == CarAssistant.Phase.LISTENING)
            Column(Modifier.weight(1f)) {
                Text(
                    when (state.phase) {
                        CarAssistant.Phase.LISTENING -> "Listening…"
                        CarAssistant.Phase.THINKING -> "…"
                        CarAssistant.Phase.SPEAKING -> state.reply
                        CarAssistant.Phase.ERROR -> state.reply
                        CarAssistant.Phase.IDLE -> ""
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                if (state.heard.isNotBlank() && state.phase != CarAssistant.Phase.SPEAKING) {
                    Text(
                        "\"${state.heard}\"",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state.missingPermission) {
                GsButton(tone = GsTone.Tonal, onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text("Grant") }
            }
            GsIconButton(Icons.Filled.Close, "Dismiss", CarAssistant::cancel)
        }
    }
}

/** A filled circle with a mic glyph, pulsing gently while actually listening. */
@Composable
private fun PulsingMic(listening: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.size(36.dp),
    ) {
        Row(horizontalArrangement = Arrangement.Center) {
            MicGlyph(Modifier.padding(8.dp).size(20.dp))
        }
    }
    // `listening` only changes what's drawn elsewhere in the row (the "Listening…" label);
    // the glyph itself doesn't need to animate for this to read clearly on a glance.
}

/** The assistant's mic, from `R.drawable.mic_24`, tinted with the current content colour. */
@Composable
fun MicGlyph(modifier: Modifier = Modifier) {
    Icon(ImageVector.vectorResource(R.drawable.mic_24), contentDescription = "Assistant", modifier = modifier)
}
