package dev.minilaunch

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

// open-meteo.com, no key needed. Only refreshed from MainActivity.onResume.
object Weather {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var inFlight = false

    fun text(): String {
        val c = Prefs.weatherCache
        if (c.isEmpty()) return ""
        return runCatching {
            val o = JSONObject(c)
            val t = o.getDouble("t").roundToInt()
            val unit = if (o.optBoolean("f")) "°F" else "°C"
            "$t$unit ${describe(o.getInt("c"))}"
        }.getOrDefault("")
    }

    fun configured() = !Prefs.weatherLat.isNaN() && !Prefs.weatherLon.isNaN()

    fun stale() = System.currentTimeMillis() - Prefs.weatherTime > Prefs.weatherRefresh * 60_000L

    fun refresh(force: Boolean, done: (String) -> Unit) {
        if (!configured() || inFlight || (!force && !stale())) return
        inFlight = true
        val lat = Prefs.weatherLat; val lon = Prefs.weatherLon; val f = Prefs.fahrenheit
        Thread {
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code" + (if (f) "&temperature_unit=fahrenheit" else "")
                val o = JSONObject(get(url)).getJSONObject("current")
                Prefs.weatherCache = JSONObject()
                    .put("t", o.getDouble("temperature_2m"))
                    .put("c", o.getInt("weather_code"))
                    .put("f", f).toString()
                main.post { done(text()) }
            } catch (_: Throwable) {
            } finally { inFlight = false }
        }.start()
    }

    class Place(val name: String, val lat: Double, val lon: Double)

    fun geocode(query: String, done: (List<Place>?) -> Unit) {
        Thread {
            val r = runCatching {
                val q = URLEncoder.encode(query, "UTF-8")
                val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&format=json"))
                val arr = o.optJSONArray("results") ?: return@runCatching emptyList()
                List(arr.length()) {
                    val p = arr.getJSONObject(it)
                    val parts = listOfNotNull(p.optString("name"), p.optString("admin1").ifEmpty { null }, p.optString("country").ifEmpty { null })
                    Place(parts.joinToString(", "), p.getDouble("latitude"), p.getDouble("longitude"))
                }
            }.getOrNull()
            main.post { done(r) }
        }.start()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "MiniLaunch")
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    fun describe(code: Int) = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80, 81, 82 -> "showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm, hail"
        else -> ""
    }
}
