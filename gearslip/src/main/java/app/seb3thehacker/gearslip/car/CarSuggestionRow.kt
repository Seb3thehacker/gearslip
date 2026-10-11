package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.car.theme.GsIconBox

/** A horizontal shelf keeps suggested destinations separate from the driver's app order. */
@Composable
internal fun CarSuggestionRow(shortcuts: List<CarSuggestions.Shortcut>) {
    val navigator = LocalCarNavigator.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Suggested destinations", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shortcuts, key = { it.id }) { shortcut ->
                GsIconBox(
                    onClick = { if (CarSuggestions.launch(shortcut)) navigator.home() },
                    modifier = Modifier.width(240.dp).heightIn(min = 88.dp),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        shortcut.icon?.let { CarGlyph(it, Modifier.size(40.dp)) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                shortcut.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            shortcut.subtitle?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
