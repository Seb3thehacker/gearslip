package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * A keyboard the host draws itself.
 *
 * Text entry in a car can't use the phone's IME: the car UI lives in a Presentation on a
 * virtual display, where a system keyboard has nowhere sensible to appear, and a phone keyboard
 * is the wrong shape for a screen a driver glances at anyway. Android Auto draws its own for
 * the same reasons, so this does too - large keys, no long-press, no prediction.
 *
 * It is a pure function of [text]: every key reports the new string and holds no state beyond
 * which layer is showing.
 */
@Composable
fun CarKeyboard(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    submitIcon: Boolean = true,
    submitImage: ImageVector? = null,
) {
    var symbols by remember { mutableStateOf(false) }
    var shifted by remember { mutableStateOf(false) }

    val rows = if (symbols) SYMBOL_ROWS else LETTER_ROWS

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    row.forEach { key ->
                        val label = if (shifted && !symbols) key.uppercase() else key
                        Key(label, Modifier.weight(1f)) {
                            onTextChange(text + label)
                            shifted = false
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Key(if (symbols) "abc" else "?123", Modifier.weight(1.4f)) { symbols = !symbols }
                if (!symbols) {
                    Key(if (shifted) "SHIFT" else "shift", Modifier.weight(1.4f)) { shifted = !shifted }
                }
                Key("space", Modifier.weight(3f)) { onTextChange("$text ") }
                Key("⌫", Modifier.weight(1.4f)) {
                    if (text.isNotEmpty()) onTextChange(text.dropLast(1))
                }
                SubmitKey(Modifier.weight(1.6f), submitIcon, submitImage, onSubmit)
            }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.height(KEY_HEIGHT).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SubmitKey(modifier: Modifier, search: Boolean, image: ImageVector?, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.height(KEY_HEIGHT).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                image ?: if (search) Icons.Filled.Search else Icons.Filled.ArrowBack,
                contentDescription = "Submit",
            )
        }
    }
}

/**
 * Big enough to hit on a bumpy road, small enough that four rows plus a field plus a few
 * results all fit a 480px-tall screen - which is the real constraint here, not the key size.
 */
private val KEY_HEIGHT = 34.dp

private val LETTER_ROWS = listOf(
    "qwertyuiop".map { it.toString() },
    "asdfghjkl".map { it.toString() },
    "zxcvbnm".map { it.toString() },
)

private val SYMBOL_ROWS = listOf(
    "1234567890".map { it.toString() },
    listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
    listOf(".", ",", "?", "!", "'", "#", "%", "*", "+", "="),
)
