package app.seb3thehacker.gearslip.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.AppSettings
import app.seb3thehacker.gearslip.BuildConfig
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRecommendedApps: () -> Unit,
    onOpenWhatsNew: () -> Unit,
    onReplayTutorial: () -> Unit,
    atUsageNotes: Boolean = false,
) {
    val scroll = rememberScrollState()
    // Opened from Home's usage notes row: usage notes is the last section, so start at the end.
    if (atUsageNotes) {
        LaunchedEffect(Unit) {
            snapshotFlow { scroll.maxValue }.first { it > 0 && it < Int.MAX_VALUE }
            scroll.scrollTo(scroll.maxValue)
        }
    }
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
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            AboutSection(onOpenWhatsNew, onReplayTutorial)
            ConnectionSection()
            if (SHOW_RECOMMENDED_APPS) AppsSection(onOpenRecommendedApps)
            CertificateSection()
            SettingsSection("Usage notes", header = USAGE_STATS_INTRO) { UsageStatsRows() }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Arrow() = Icon(
    Icons.AutoMirrored.Filled.KeyboardArrowRight,
    contentDescription = null,
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** What's new, updates and the setup guide, one tap from the top. */
@Composable
private fun AboutSection(onOpenWhatsNew: () -> Unit, onReplayTutorial: () -> Unit) {
    SettingsSection {
        SettingsRow(
            "What's new",
            Modifier.clickable(onClick = onOpenWhatsNew),
            subtitle = "Version ${BuildConfig.VERSION_NAME}",
            icon = Icons.Filled.Star,
            trailing = { Arrow() },
        )
        SettingsDivider()
        UpdateRow()
        SettingsDivider()
        UpdateNotifyRow()
        SettingsDivider()
        SettingsRow(
            "Replay the setup guide",
            Modifier.clickable(onClick = onReplayTutorial),
            subtitle = "Go through the first-run steps again",
            icon = Icons.Filled.Refresh,
            trailing = { Arrow() },
        )
    }
}

/** Off, a stock Android Auto app or head unit answers the USB connection instead of Gearslip. */
@Composable
private fun ConnectionSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppSettings.gearslipEnabled(context)) }
    SettingsSection("Connection") {
        SettingsRow(
            "Use Gearslip in the car",
            Modifier.toggleable(enabled, role = Role.Switch) {
                enabled = it
                AppSettings.setGearslipEnabled(context, it)
            },
            subtitle = if (enabled) "Gearslip answers when you plug into a car" else "Off: Android Auto can take the connection instead",
            icon = Icons.Filled.Settings,
            trailing = { Switch(checked = enabled, onCheckedChange = null) },
        )
    }
}

@Composable
private fun AppsSection(onOpenRecommendedApps: () -> Unit) {
    SettingsSection("Apps") {
        SettingsRow(
            "Recommended apps",
            Modifier.clickable(onClick = onOpenRecommendedApps),
            icon = Icons.Filled.Star,
            trailing = { Arrow() },
        )
    }
}

/** The projection identity is part of this build; only its public details are shown. */
@Composable
private fun CertificateSection() {
    val context = LocalContext.current
    val summary by produceState(CertSummary.cached, context) {
        value = withContext(Dispatchers.Default) { CertSummary.read(context) }
    }
    var detailsOpen by remember { mutableStateOf(false) }
    val s = summary
    SettingsSection("Certificate", footer = "Included with Gearslip. Certificate updates come with app updates.") {
        SettingsRow(
            s?.headline ?: "Checking…",
            Modifier.clickable(enabled = s != null) { detailsOpen = !detailsOpen },
            subtitle = s?.validUntil?.let { if (s.expired) "Expired on $it" else "Valid until $it" },
            icon = if (s?.expired == true) Icons.Filled.Warning else Icons.Filled.Lock,
            tint = if (s?.expired == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            trailing = {
                Icon(
                    if (detailsOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (detailsOpen) "Hide details" else "Show details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        AnimatedVisibility(detailsOpen && s != null) {
            Column(
                Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                s?.subject?.let { Detail("Subject", it) }
                s?.issuer?.let { Detail("Issuer", it) }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
