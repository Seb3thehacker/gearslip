package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/**
 * Draws the raw video frame with pixel rulers, for measuring what the head unit actually shows.
 *
 * Numbers are frame pixels. Read the last number visible on each edge to see how much the head
 * unit crops there (the frame is 800 wide by 480 tall when the ruler runs to those values), and
 * tap anywhere: the crosshair is drawn where the phone believes the touch landed, so a mismatch
 * with your finger is the touch offset. Ignores vehicle insets on purpose.
 */
@Composable
fun CalibrationApp() {
    val navigator = LocalCarNavigator.current
    val measurer = rememberTextMeasurer()
    var touch by remember { mutableStateOf<Offset?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    touch = down.position
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val label = TextStyle(color = Color.White, fontSize = 11.sp)
            val faint = Color(0x55FFFFFF)
            val w = size.width
            val h = size.height

            var y = 0
            while (y <= h) {
                drawLine(if (y % 100 == 0) Color.White else faint, Offset(0f, y.toFloat()), Offset(w, y.toFloat()), strokeWidth = 1f)
                if (y % 20 == 0) {
                    drawText(measurer, "$y", Offset(2f, y.toFloat()), style = label)
                    drawText(measurer, "$y", Offset(w - 30f, y.toFloat()), style = label)
                }
                y += 20
            }
            var x = 0
            while (x <= w) {
                drawLine(if (x % 100 == 0) Color.White else faint, Offset(x.toFloat(), 0f), Offset(x.toFloat(), h), strokeWidth = 1f)
                if (x % 50 == 0 && x > 0) {
                    drawText(measurer, "$x", Offset(x.toFloat() + 2f, 2f), style = label)
                    drawText(measurer, "$x", Offset(x.toFloat() + 2f, h - 46f), style = label)
                }
                x += 20
            }
            // Frame border, drawn last and inset by 1px so it is the outermost thing that can show.
            drawRect(Color.Red, topLeft = Offset(1f, 1f), size = androidx.compose.ui.geometry.Size(w - 2f, h - 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            drawText(measurer, "frame ${w.toInt()}x${h.toInt()}", Offset(w / 2 - 50f, h / 2 - 60f), style = TextStyle(color = Color.Yellow, fontSize = 14.sp))

            touch?.let { t ->
                drawLine(Color.Green, Offset(t.x, 0f), Offset(t.x, h), strokeWidth = 2f)
                drawLine(Color.Green, Offset(0f, t.y), Offset(w, t.y), strokeWidth = 2f)
                drawText(measurer, "touch ${t.x.toInt()},${t.y.toInt()}", Offset(w / 2 - 50f, h / 2 + 40f), style = TextStyle(color = Color.Green, fontSize = 14.sp))
            }
        }
        Button(onClick = { navigator.home() }, modifier = Modifier.align(Alignment.Center)) {
            Text("Exit calibration")
        }
    }
}
