package app.seb3thehacker.gearslip.host

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.InputCallbackDelegate
import androidx.car.app.model.OnClickDelegate
import androidx.car.app.model.OnContentRefreshDelegate
import androidx.car.app.model.SearchCallbackDelegate
import androidx.car.app.model.TabCallbackDelegate
import androidx.car.app.OnDoneCallback
import androidx.car.app.serialization.Bundleable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.seb3thehacker.gearslip.GearslipLog
import java.util.concurrent.TimeUnit

/**
 * Turns the Car App Library's model objects into things Compose can draw.
 *
 * A templated app describes its screen in these types and says nothing about how they should
 * look - that is the host's job, and every host looks different. These helpers are the seam
 * between the app's description and Gearslip's rendering of it.
 */

/** The app's text, preferring the longest variant that will fit; variants are ordered longest first. */
fun CarText?.text(): String = this?.takeIf { !it.isEmpty }?.toCharSequence()?.toString().orEmpty()

/**
 * Loads an icon the app described.
 *
 * A [CarIcon] is either a standard type the host is expected to draw itself, or an IconCompat
 * pointing into the app's own resources - which is why this needs a Context that can see the
 * app, and why a failure here is a missing glyph rather than an error worth stopping for.
 */
fun CarIcon?.image(context: Context): ImageBitmap? {
    val icon = this?.icon ?: return null
    val drawable: Drawable = runCatching { icon.loadDrawable(context) }.getOrNull() ?: return null
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: ICON_FALLBACK_PX
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: ICON_FALLBACK_PX
    return runCatching { drawable.toBitmap(width, height).asImageBitmap() }.getOrNull()
}

/** Standard icons carry no bitmap - the host draws its own, so callers need to know which. */
val CarIcon?.standardType: Int? get() = this?.takeIf { it.icon == null }?.type

/**
 * Resolves a colour the app asked for. The named colours deliberately stay close to what a
 * driver expects them to mean rather than matching the app's brand.
 */
fun CarColor?.color(dark: Boolean, default: Color): Color = when (this?.type) {
    null, CarColor.TYPE_DEFAULT -> default
    CarColor.TYPE_CUSTOM -> Color(if (dark) colorDark else color)
    CarColor.TYPE_RED -> Color(0xFFE53935)
    CarColor.TYPE_GREEN -> Color(0xFF43A047)
    CarColor.TYPE_BLUE -> Color(0xFF1E88E5)
    CarColor.TYPE_YELLOW -> Color(0xFFFDD835)
    else -> default
}

/** "500 ft", "2.4 mi" - the app picks the unit, the host formats it. */
fun Distance?.display(): String {
    val distance = this ?: return ""
    val unit = when (displayUnit) {
        Distance.UNIT_METERS -> "m"
        Distance.UNIT_KILOMETERS, Distance.UNIT_KILOMETERS_P1 -> "km"
        Distance.UNIT_MILES, Distance.UNIT_MILES_P1 -> "mi"
        Distance.UNIT_FEET -> "ft"
        Distance.UNIT_YARDS -> "yd"
        else -> ""
    }
    val decimals = when (displayUnit) {
        Distance.UNIT_KILOMETERS_P1, Distance.UNIT_MILES_P1 -> 1
        Distance.UNIT_KILOMETERS, Distance.UNIT_MILES -> if (displayDistance < 10) 1 else 0
        else -> 0
    }
    return "%.${decimals}f $unit".format(distance.displayDistance)
}

/** "18 min" from the app's remaining-time estimate. */
fun remainingTime(seconds: Long): String {
    if (seconds <= 0) return ""
    val hours = TimeUnit.SECONDS.toHours(seconds)
    val minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60
    return if (hours > 0) "$hours hr $minutes min" else "$minutes min"
}

/**
 * Tells the app something was tapped.
 *
 * [OnClickDelegate.isParkedOnly] marks an action the app itself considers unsafe while moving.
 * Gearslip has no speed signal yet, so these are allowed through and the decision is recorded
 * here as the place to gate them once the sensor channel exists.
 */
fun OnClickDelegate?.click(label: String = "action") {
    val delegate = this ?: return
    runCatching { delegate.sendClick(doneCallback("a $label tap")) }
        .onFailure { GearslipLog.w("host: could not send a $label tap: ${it.message}") }
}

/**
 * Search is a conversation: the host owns the text and the keyboard, and tells the app about
 * every keystroke so it can update its results as you type. [submitted] is the explicit "go".
 */
fun SearchCallbackDelegate?.textChanged(text: String) {
    val delegate = this ?: return
    runCatching { delegate.sendSearchTextChanged(text, doneCallback("search text")) }
        .onFailure { GearslipLog.w("host: could not send search text: ${it.message}") }
}

fun SearchCallbackDelegate?.submitted(text: String) {
    val delegate = this ?: return
    runCatching { delegate.sendSearchSubmitted(text, doneCallback("search submit")) }
        .onFailure { GearslipLog.w("host: could not submit a search: ${it.message}") }
}

/** Tells the app which tab the driver picked; the app answers with a new template. */
fun TabCallbackDelegate?.tabSelected(contentId: String) {
    val delegate = this ?: return
    runCatching { delegate.sendTabSelected(contentId, doneCallback("tab")) }
        .onFailure { GearslipLog.w("host: could not select a tab: ${it.message}") }
}

/** Free text typed into a sign-in field, reported the same way search text is. */
fun InputCallbackDelegate?.inputChanged(text: String) {
    val delegate = this ?: return
    runCatching { delegate.sendInputTextChanged(text, doneCallback("input text")) }
        .onFailure { GearslipLog.w("host: could not send input text: ${it.message}") }
}

fun InputCallbackDelegate?.inputSubmitted(text: String) {
    val delegate = this ?: return
    runCatching { delegate.sendInputSubmitted(text, doneCallback("input submit")) }
        .onFailure { GearslipLog.w("host: could not submit input: ${it.message}") }
}

/** Tells the app the driver entered or left pan mode, so it can hide its own map controls. */
fun androidx.car.app.navigation.model.PanModeDelegate?.panModeChanged(active: Boolean) {
    val delegate = this ?: return
    runCatching { delegate.sendPanModeChanged(active, doneCallback("pan mode")) }
        .onFailure { GearslipLog.w("host: could not send pan mode: ${it.message}") }
}

/** Tells the app a toggle row was switched; it answers with a template showing the new state. */
fun androidx.car.app.model.OnCheckedChangeDelegate?.checkedChanged(checked: Boolean) {
    val delegate = this ?: return
    runCatching { delegate.sendCheckedChange(checked, doneCallback("toggle")) }
        .onFailure { GearslipLog.w("host: could not send a toggle: ${it.message}") }
}

/** Tells the app which option of a single-choice list was picked. */
fun androidx.car.app.model.OnSelectedDelegate?.selected(index: Int) {
    val delegate = this ?: return
    runCatching { delegate.sendSelected(index, doneCallback("selection")) }
        .onFailure { GearslipLog.w("host: could not send a selection: ${it.message}") }
}

/** The driver asked for fresher content, e.g. by pulling a place list. */
fun OnContentRefreshDelegate?.refresh() {
    val delegate = this ?: return
    runCatching { delegate.sendContentRefreshRequested(doneCallback("refresh")) }
        .onFailure { GearslipLog.w("host: could not request a refresh: ${it.message}") }
}

private fun doneCallback(label: String) = object : OnDoneCallback {
    // onSuccess is annotated nullable and onFailure isn't - the asymmetry is real.
    override fun onSuccess(response: Bundleable?) = Unit
    override fun onFailure(response: Bundleable) = GearslipLog.w("host: the app rejected $label")
}

/** An action with nothing to show is one the host should skip rather than draw as a blank. */
fun Action.isDrawable(): Boolean =
    icon != null || title.text().isNotEmpty() || type != Action.TYPE_CUSTOM

private const val ICON_FALLBACK_PX = 48
