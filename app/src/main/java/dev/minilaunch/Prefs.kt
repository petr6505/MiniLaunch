package dev.minilaunch

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** All user configuration, backed by a single SharedPreferences file. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.getSharedPreferences("mini", Context.MODE_PRIVATE)
    }

    /** Bumped on every write so the home screen knows to re-apply settings. */
    val version: Int get() = sp.getInt("_v", 0)

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).putInt("_v", version + 1).apply()
    }

    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    fun setInt(k: String, v: Int) = edit { putInt(k, v) }
    fun setBool(k: String, v: Boolean) = edit { putBoolean(k, v) }
    fun setStr(k: String, v: String) = edit { putString(k, v) }
    fun getInt(k: String, d: Int) = int(k, d)
    fun getBool(k: String, d: Boolean) = bool(k, d)
    fun getStr(k: String, d: String) = str(k, d)

    // ---- appearance ----
    var textColor: Int get() = int("textColor", 0xFFFFFFFF.toInt()); set(v) = setInt("textColor", v)
    var dimColor: Int get() = int("dimColor", 0xFF000000.toInt()); set(v) = setInt("dimColor", v)
    /** 0..100 – opacity of the solid colour drawn over the wallpaper. */
    var dim: Int get() = int("dim", 35); set(v) = setInt("dim", v)
    var appSize: Int get() = int("appSize", 24); set(v) = setInt("appSize", v)
    var appSpacing: Int get() = int("appSpacing", 14); set(v) = setInt("appSpacing", v)
    var clockSize: Int get() = int("clockSize", 56); set(v) = setInt("clockSize", v)
    var infoSize: Int get() = int("infoSize", 15); set(v) = setInt("infoSize", v)
    var drawerSize: Int get() = int("drawerSize", 20); set(v) = setInt("drawerSize", v)
    /** 0 left, 1 center, 2 right */
    var align: Int get() = int("align", 0); set(v) = setInt("align", v)
    var headerAlign: Int get() = int("headerAlign", 0); set(v) = setInt("headerAlign", v)
    var drawerAlign: Int get() = int("drawerAlign", 0); set(v) = setInt("drawerAlign", v)
    /** 0 as-is, 1 lowercase, 2 UPPERCASE */
    var textCase: Int get() = int("textCase", 1); set(v) = setInt("textCase", v)
    /** 0 top, 1 center, 2 bottom */
    var appsVPos: Int get() = int("appsVPos", 1); set(v) = setInt("appsVPos", v)
    var sidePadding: Int get() = int("sidePadding", 28); set(v) = setInt("sidePadding", v)
    var showStatusBar: Boolean get() = bool("statusBar", true); set(v) = setBool("statusBar", v)
    var boldClock: Boolean get() = bool("boldClock", false); set(v) = setBool("boldClock", v)
    /** Gentle transitions (only while you interact – nothing animates when idle). */
    var motion: Boolean get() = bool("motion", true); set(v) = setBool("motion", v)
    var showGreeting: Boolean get() = bool("greeting", true); set(v) = setBool("greeting", v)
    var greetName: String get() = str("greetName", ""); set(v) = setStr("greetName", v)

    // ---- header ----
    var showClock: Boolean get() = bool("showClock", true); set(v) = setBool("showClock", v)
    var showDate: Boolean get() = bool("showDate", true); set(v) = setBool("showDate", v)
    var showBattery: Boolean get() = bool("showBattery", true); set(v) = setBool("showBattery", v)
    var showWeather: Boolean get() = bool("showWeather", true); set(v) = setBool("showWeather", v)
    /** 0 follow system, 1 24h, 2 12h */
    var clockMode: Int get() = int("clockMode", 0); set(v) = setInt("clockMode", v)
    var showSeconds: Boolean get() = bool("showSeconds", false); set(v) = setBool("showSeconds", v)
    var dateFormat: String get() = str("dateFormat", "EEE, d MMM"); set(v) = setStr("dateFormat", v)
    /** Info line ordering is fixed (date · battery · weather) but each piece can be on its own line. */
    var infoOneLine: Boolean get() = bool("infoOneLine", true); set(v) = setBool("infoOneLine", v)

    // ---- home apps ----
    var homeApps: List<String>
        get() = jsonList(str("homeApps", "[]"))
        set(v) = setStr("homeApps", JSONArray(v).toString())
    var maxHomeApps: Int get() = int("maxHomeApps", 8); set(v) = setInt("maxHomeApps", v)

    // ---- labels / hidden ----
    private var labelCache: Map<String, String>? = null
    fun label(key: String): String? {
        val c = labelCache ?: labels().let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            .also { labelCache = it }
        return c[key]
    }
    fun setLabel(key: String, label: String?) {
        val o = labels(); if (label.isNullOrBlank()) o.remove(key) else o.put(key, label)
        labelCache = null
        setStr("labels", o.toString())
    }
    private fun labels() = runCatching { JSONObject(str("labels", "{}")) }.getOrDefault(JSONObject())

    private var hiddenCache: Set<String>? = null
    var hidden: Set<String>
        get() = hiddenCache ?: sp.getStringSet("hidden", emptySet())!!.toSet().also { hiddenCache = it }
        set(v) { hiddenCache = null; edit { putStringSet("hidden", v) } }

    // ---- drawer ----
    var autoLaunch: Boolean get() = bool("autoLaunch", true); set(v) = setBool("autoLaunch", v)
    var drawerKeyboard: Boolean get() = bool("drawerKeyboard", true); set(v) = setBool("drawerKeyboard", v)
    var searchHidden: Boolean get() = bool("searchHidden", false); set(v) = setBool("searchHidden", v)
    var fuzzySearch: Boolean get() = bool("fuzzySearch", true); set(v) = setBool("fuzzySearch", v)
    var drawerShowAll: Boolean get() = bool("drawerShowAll", true); set(v) = setBool("drawerShowAll", v)
    var searchWebFallback: Boolean get() = bool("webFallback", true); set(v) = setBool("webFallback", v)

    // ---- gestures ----
    fun gesture(g: String): String = str("g_$g", Gestures.default(g))
    fun setGesture(g: String, action: String) = setStr("g_$g", action)

    // ---- widgets ----
    var widgets: JSONArray
        get() = runCatching { JSONArray(str("widgets", "[]")) }.getOrDefault(JSONArray())
        set(v) = setStr("widgets", v.toString())
    /** Default position for newly added widgets (see WidgetHost.positions). */
    var widgetDefaultPos: Int get() = int("widgetPos", 0); set(v) = setInt("widgetPos", v)

    // ---- usage limits ----
    /** pkg -> {"m": minutes/day, "d": delay seconds} */
    var limits: JSONObject
        get() = runCatching { JSONObject(str("limits", "{}")) }.getOrDefault(JSONObject())
        set(v) = setStr("limits", v.toString())
    var remindEvery: Int get() = int("remindEvery", 5); set(v) = setInt("remindEvery", v)
    var limitBlocks: Boolean get() = bool("limitBlocks", false); set(v) = setBool("limitBlocks", v)

    // ---- weather ----
    var weatherCity: String get() = str("wCity", ""); set(v) = setStr("wCity", v)
    var weatherLat: Float get() = sp.getFloat("wLat", Float.NaN); set(v) = edit { putFloat("wLat", v) }
    var weatherLon: Float get() = sp.getFloat("wLon", Float.NaN); set(v) = edit { putFloat("wLon", v) }
    var fahrenheit: Boolean get() = bool("wF", false); set(v) = setBool("wF", v)
    var weatherRefresh: Int get() = int("wRefresh", 60); set(v) = setInt("wRefresh", v)

    // Weather cache is written without bumping the version (it is data, not config).
    var weatherCache: String
        get() = str("wCache", "")
        set(v) { sp.edit().putString("wCache", v).putLong("wTime", System.currentTimeMillis()).apply() }
    val weatherTime: Long get() = sp.getLong("wTime", 0)
    fun clearWeatherCache() { sp.edit().remove("wCache").remove("wTime").apply() }

    /** Own-widget data (todo items etc.) – written without a version bump to avoid full re-layout. */
    fun data(k: String): String = sp.getString("d_$k", "") ?: ""
    fun setData(k: String, v: String) { sp.edit().putString("d_$k", v).apply() }

    fun jsonList(s: String): List<String> = runCatching {
        val a = JSONArray(s); List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    // ---- backup ----
    fun export(): String {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            if (k.startsWith("w") && (k == "wCache" || k == "wTime")) continue
            when (v) {
                is Set<*> -> o.put(k, JSONObject().put("set", JSONArray(v.toList())))
                is Float -> o.put(k, JSONObject().put("float", v.toDouble()))
                is Long -> o.put(k, JSONObject().put("long", v))
                else -> o.put(k, v)
            }
        }
        return o.toString(2)
    }

    fun import(json: String) {
        val o = JSONObject(json)
        val e = sp.edit().clear()
        for (k in o.keys()) {
            when (val v = o.get(k)) {
                is JSONObject -> when {
                    v.has("set") -> e.putStringSet(k, jsonList(v.getJSONArray("set").toString()).toSet())
                    v.has("float") -> e.putFloat(k, v.getDouble("float").toFloat())
                    v.has("long") -> e.putLong(k, v.getLong("long"))
                }
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putInt(k, v.toInt())
                is String -> e.putString(k, v)
            }
        }
        e.putInt("_v", version + 1).apply()
        labelCache = null; hiddenCache = null
    }
}
