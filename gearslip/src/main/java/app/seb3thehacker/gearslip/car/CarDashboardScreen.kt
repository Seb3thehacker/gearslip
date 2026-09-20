package app.seb3thehacker.gearslip.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.LocationManager
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.GearslipLog
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Opened from the nav bar clock: the week's calendar and the current weather, at a glance. Both
 * need permissions granted on the phone (READ_CALENDAR, location) - if either was declined
 * this just shows an empty state instead of asking again from inside the car UI.
 */
@Composable
fun CarDashboardScreen() {
    val context = LocalContext.current.applicationContext

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        WeatherCard(context)
        AgendaCard(context)
    }
}

@Composable
private fun WeatherCard(context: Context) {
    val weather by produceState<Weather?>(Weather.LOADING) {
        value = withContext(Dispatchers.IO) { fetchWeather(context) }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            val w = weather
            when {
                w === Weather.LOADING -> Text("Getting weather…", style = MaterialTheme.typography.bodyLarge)
                w == null -> Text(
                    "Weather unavailable - no location fix yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "${w.tempC.toInt()}°C  ·  ${w.description}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun AgendaCard(context: Context) {
    val events by produceState<List<AgendaEvent>?>(null) {
        value = withContext(Dispatchers.IO) { fetchAgenda(context) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Coming up", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        val list = events
        when {
            list == null -> Text("Loading calendar…", style = MaterialTheme.typography.bodyMedium)
            list.isEmpty() -> Text(
                "Nothing in the next week.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> list.forEach { EventRow(it) }
        }
    }
}

@Composable
private fun EventRow(event: AgendaEvent) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                eventWhen(event),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                event.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Today" events show just a time; later days get a weekday so a week's list stays readable. */
private fun eventWhen(event: AgendaEvent): String {
    val zone = if (event.allDay) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    val start = Calendar.getInstance(zone).apply { timeInMillis = event.startMillis }
    val today = Calendar.getInstance()
    val sameDay = start.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        start.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val time = if (event.allDay) "All day" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.startMillis))
    if (sameDay || (event.allDay && start.before(today))) return time
    return SimpleDateFormat("EEE", Locale.getDefault()).apply { timeZone = zone }.format(Date(event.startMillis)) + " " + time
}

private data class Weather(val tempC: Double, val description: String) {
    companion object { val LOADING = Weather(Double.NaN, "") }
}

private data class AgendaEvent(val title: String, val startMillis: Long, val allDay: Boolean)

private const val WEATHER_TTL_MS = 60 * 60 * 1000L
private var cachedWeather: Weather? = null
private var cachedWeatherAt = 0L

/** Served from memory for up to an hour: weather barely changes, and each call is a network request. */
private fun fetchWeather(context: Context): Weather? {
    val now = System.currentTimeMillis()
    cachedWeather?.let { if (now - cachedWeatherAt < WEATHER_TTL_MS) return it }
    return loadWeather(context)?.also { cachedWeather = it; cachedWeatherAt = now }
}

/** Null means no fix or no permission; the card shows a plain empty state either way. */
private fun loadWeather(context: Context): Weather? {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return null
    }
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val location = manager.allProviders
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?: return null

    return runCatching {
        // Open-Meteo: no API key, no account - the simplest thing that gives a real reading.
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}" +
                "&longitude=${location.longitude}&current_weather=true",
        )
        val body = (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 5_000
            readTimeout = 5_000
            inputStream.bufferedReader().use { it.readText() }
        }
        val current = JSONObject(body).getJSONObject("current_weather")
        Weather(current.getDouble("temperature"), weatherDescription(current.getInt("weathercode")))
    }.onFailure { GearslipLog.w("weather fetch failed: ${it.message}") }.getOrNull()
}

/** Open-Meteo's WMO weather codes, collapsed to what's worth a glance from the driver's seat. */
private fun weatherDescription(code: Int): String = when (code) {
    0 -> "Clear"
    1, 2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    in 51..57 -> "Drizzle"
    in 61..67 -> "Rain"
    in 71..77 -> "Snow"
    in 80..82 -> "Rain showers"
    in 85..86 -> "Snow showers"
    in 95..99 -> "Thunderstorm"
    else -> "-"
}

/**
 * Empty list means no permission or nothing scheduled. Looks from the start of today so an
 * event already under way still shows, a week ahead so tomorrow's does too, and skips ones
 * that have finished.
 */
private fun fetchAgenda(context: Context): List<AgendaEvent> {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }

    val now = System.currentTimeMillis()
    val startOfToday = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val end = startOfToday + 7 * 24 * 60 * 60 * 1000L
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        .appendPath(startOfToday.toString())
        .appendPath(end.toString())
        .build()

    val events = mutableListOf<AgendaEvent>()
    val projection = arrayOf(
        CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
    )
    runCatching {
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
            ?.use { cursor: Cursor ->
                while (cursor.moveToNext() && events.size < 10) {
                    val allDay = cursor.getInt(3) == 1
                    // All-day events are stored in UTC midnight, so "ended" means the day is over, not the instant.
                    if (!allDay && cursor.getLong(2) < now) continue
                    events.add(AgendaEvent(cursor.getString(0) ?: "(untitled)", cursor.getLong(1), allDay))
                }
            }
    }.onFailure { GearslipLog.w("calendar query failed: ${it.message}") }
    return events
}
