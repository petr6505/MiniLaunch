package dev.minilaunch

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

class SettingsActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var box: LinearLayout
    private lateinit var titleView: TextView
    private val stack = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = Ui.tv(this, "", 22f, Ui.FG, bold = true).apply {
            val p = Ui.dp(this@SettingsActivity, 20)
            setPadding(p, p, p, Ui.dp(this@SettingsActivity, 8))
            background = Ui.ripple(); setOnClickListener { back() }
        }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@SettingsActivity, 20)
            setPadding(p, 0, p, Ui.dp(this@SettingsActivity, 40))
        }
        scroll = ScrollView(this).apply { addView(box); isVerticalScrollBarEnabled = false }
        root.addView(titleView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, wi ->
            val i = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom); wi
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        if (Apps.all.isEmpty()) Apps.load(this)
        stack.addLast("main")
        intent.getStringExtra("page")?.let { stack.addLast(it) }
        render()
    }

    override fun onResume() {
        super.onResume()
        WidgetPicker.onAdded = { render(keepScroll = true) }
        render(keepScroll = true)
    }

    private fun back() {
        if (stack.size > 1) { stack.removeLast(); render() } else finish()
    }

    private fun go(page: String) { stack.addLast(page); render() }

    private fun render(keepScroll: Boolean = false) {
        val y = scroll.scrollY
        box.removeAllViews()
        val page = stack.last()
        titleView.text = (if (stack.size > 1) "< " else "") + (titles[page] ?: page)
        when (page) {
            "main" -> main()
            "appearance" -> appearance()
            "header" -> header()
            "home" -> homeApps()
            "drawer" -> drawerPage()
            "gestures" -> gestures()
            "widgets" -> widgets()
            "weather" -> weather()
            "wellbeing" -> wellbeing()
            "hidden" -> hidden()
            "system" -> system()
            "backup" -> backup()
        }
        if (keepScroll) scroll.post { scroll.scrollTo(0, y) } else scroll.scrollTo(0, 0)
    }

    private val titles = mapOf(
        "main" to "settings", "appearance" to "appearance", "header" to "clock & status",
        "home" to "home apps", "drawer" to "app drawer & search", "gestures" to "gestures",
        "widgets" to "widgets", "weather" to "weather", "wellbeing" to "digital wellbeing",
        "hidden" to "hidden apps", "system" to "system & permissions", "backup" to "backup & reset",
    )

    private fun section(t: String) {
        box.addView(Ui.tv(this, t.uppercase(), 12f, Ui.DIM).apply {
            setPadding(0, Ui.dp(this@SettingsActivity, 22), 0, Ui.dp(this@SettingsActivity, 4))
            letterSpacing = 0.1f
        })
    }

    private fun note(t: String) {
        box.addView(Ui.tv(this, t, 13f, Ui.DIM).apply {
            setPadding(0, Ui.dp(this@SettingsActivity, 6), 0, Ui.dp(this@SettingsActivity, 6))
            setLineSpacing(0f, 1.2f)
        })
    }

    private fun row(label: String, value: String? = null, onClick: (() -> Unit)?) {
        box.addView(Ui.row(this, label, value, onClick))
    }

    private fun nav(label: String, page: String, sub: String? = null) = row("$label  >", sub) { go(page) }

    private fun toggle(label: String, sub: String? = null, get: () -> Boolean, set: (Boolean) -> Unit) {
        val l = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dp(this@SettingsActivity, 12)
            setPadding(0, p, 0, p)
            background = Ui.ripple()
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.tv(this, label, 16f))
        if (sub != null) texts.addView(Ui.tv(this, sub, 13f, Ui.DIM).apply { setPadding(0, Ui.dp(this@SettingsActivity, 4), 0, 0) })
        val state = Ui.tv(this, if (get()) "[on ]" else "[off]", 15f, if (get()) Ui.FG else Ui.DIM, bold = get())
        l.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        l.addView(state)
        l.setOnClickListener { set(!get()); render(keepScroll = true) }
        box.addView(l)
    }

    private fun choice(label: String, opts: List<String>, get: () -> Int, set: (Int) -> Unit) =
        row(label, opts.getOrElse(get()) { "?" }) {
            Ui.choice(this, label, opts, get()) { set(it); render(keepScroll = true) }
        }

    private fun slider(label: String, min: Int, max: Int, suffix: String, get: () -> Int, set: (Int) -> Unit) =
        row(label, "${get()}$suffix") {
            Ui.slider(this, label, min, max, get(), suffix) { set(it); render(keepScroll = true) }
        }

    private fun color(label: String, get: () -> Int, set: (Int) -> Unit) {
        val c = get()
        val name = Ui.colorPresets.firstOrNull { it.second == c }?.first ?: Ui.hex(c)
        box.addView(Ui.row(this, label, "■ $name") {
            Ui.color(this, label, get()) { set(it); render(keepScroll = true) }
        }.also { ((it as LinearLayout).getChildAt(1) as TextView).setTextColor(if (c == 0xFF000000.toInt()) Ui.DIM else c) })
    }

    private val alignOpts = listOf("left", "center", "right")

    // stored in sp/dp, shown as % of the default
    private fun sizeSlider(label: String, def: Int, min: Int, max: Int, get: () -> Int, set: (Int) -> Unit) {
        val fmt = { v: Int -> "${Math.round(v * 100f / def)}%" }
        row(label, fmt(get())) { Ui.slider(this, label, min, max, get(), fmt = fmt) { set(it); render(true) } }
    }

    private val density get() = resources.displayMetrics.density
    private fun pctOfWidth(dp: Int) = "${Math.round(dp * density * 100f / resources.displayMetrics.widthPixels)}%"
    private fun pctOfHeight(dp: Int) = "${Math.round(dp * density * 100f / resources.displayMetrics.heightPixels)}%" 

    private fun main() {
        val isDefault = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
        if (!isDefault) {
            row("! set MiniLaunch as default home app", "tap to choose it as your launcher") { requestHome() }
        }
        nav("appearance", "appearance", "colours, sizes, alignment, case")
        nav("clock & status", "header", "time, date, battery, weather line")
        nav("home apps", "home", "${Prefs.homeApps.size} of max ${Prefs.maxHomeApps}")
        nav("app drawer & search", "drawer")
        nav("gestures", "gestures", "swipes, taps, double tap, long press")
        nav("widgets", "widgets", "${Prefs.widgets.length()} added · positions & preview")
        nav("weather", "weather", Prefs.weatherCity.ifEmpty { "not set" })
        nav("digital wellbeing", "wellbeing", "app limits, mindful pause, grayscale")
        nav("hidden apps", "hidden", "${Prefs.hidden.size} hidden")
        nav("system & permissions", "system", "recents suggestions, lock, default launcher")
        nav("backup & reset", "backup")
        note("\nminilaunch · zero background services · text only.\nall changes apply when you go back home.")
    }

    private fun appearance() {
        section("colour")
        color("text colour", { Prefs.textColor }) { Prefs.textColor = it }
        color("wallpaper tint colour", { Prefs.dimColor }) { Prefs.dimColor = it }
        slider("wallpaper tint strength", 0, 100, "%", { Prefs.dim }) { Prefs.dim = it }
        note("100% hides the wallpaper completely - pure black is easiest on OLED battery.")
        slider("drawer background", 0, 100, "%", { Prefs.getInt("drawerDim", 88) }) { Prefs.setInt("drawerDim", it) }
        section("text")
        choice("letter case", listOf("as-is", "lowercase", "UPPERCASE"), { Prefs.textCase }) { Prefs.textCase = it }
        sizeSlider("home app size", 24, 12, 48, { Prefs.appSize }) { Prefs.appSize = it }
        sizeSlider("home app spacing", 14, 0, 48, { Prefs.appSpacing }) { Prefs.appSpacing = it }
        sizeSlider("drawer text size", 20, 12, 40, { Prefs.drawerSize }) { Prefs.drawerSize = it }
        sizeSlider("clock size", 56, 16, 120, { Prefs.clockSize }) { Prefs.clockSize = it }
        sizeSlider("date/battery/weather size", 15, 10, 30, { Prefs.infoSize }) { Prefs.infoSize = it }
        section("layout")
        choice("home apps alignment", alignOpts, { Prefs.align }) { Prefs.align = it }
        choice("clock & status alignment", alignOpts, { Prefs.headerAlign }) { Prefs.headerAlign = it }
        choice("drawer alignment", alignOpts, { Prefs.drawerAlign }) { Prefs.drawerAlign = it }
        choice("home apps vertical position", listOf("top", "center", "bottom"), { Prefs.appsVPos }) { Prefs.appsVPos = it }
        row("side margin", pctOfWidth(Prefs.sidePadding) + " of screen width") {
            Ui.slider(this, "side margin", 0, 80, Prefs.sidePadding, fmt = { pctOfWidth(it) }) { Prefs.sidePadding = it; render(true) }
        }
        toggle("show status bar", null, { Prefs.showStatusBar }) { Prefs.showStatusBar = it }
        section("feel")
        toggle("gentle motion", "soft fades & drifts when you open things - nothing moves while idle", { Prefs.motion }) { Prefs.motion = it }
        section("presets")
        row("terminal", "green on black") { preset(0xFF33FF66.toInt(), 100) }
        row("paper", "black on white") { preset(0xFF111111.toInt(), 100, tint = 0xFFF2F0EA.toInt()) }
        row("amber", "amber on black") { preset(0xFFFFB000.toInt(), 100) }
        row("wallpaper", "white over a lightly dimmed wallpaper") { preset(0xFFFFFFFF.toInt(), 35) }
    }

    private fun preset(text: Int, dim: Int, tint: Int = 0xFF000000.toInt()) {
        Prefs.textColor = text; Prefs.dim = dim; Prefs.dimColor = tint; render(keepScroll = true)
    }

    private fun header() {
        section("greeting")
        toggle("show greeting", "'good morning.' above the clock", { Prefs.showGreeting }) { Prefs.showGreeting = it }
        row("your name", Prefs.greetName.ifEmpty { "not set - optional" }) {
            Ui.input(this, "your name", Prefs.greetName, "optional") { Prefs.greetName = it.trim(); render(true) }
        }
        section("clock")
        toggle("show clock", null, { Prefs.showClock }) { Prefs.showClock = it }
        choice("time format", listOf("follow system", "24-hour", "12-hour"), { Prefs.clockMode }) { Prefs.clockMode = it }
        toggle("show seconds", "slightly more redraws - off saves battery", { Prefs.showSeconds }) { Prefs.showSeconds = it }
        toggle("bold clock", null, { Prefs.boldClock }) { Prefs.boldClock = it }
        section("status line")
        toggle("show date", null, { Prefs.showDate }) { Prefs.showDate = it }
        val dateFormats = listOf("EEE, d MMM", "EEEE d MMMM", "d.M.yyyy", "yyyy-MM-dd", "EEE dd/MM", "MMM d, yyyy")
        row("date format", Prefs.dateFormat + "   e.g. " + android.text.format.DateFormat.format(Prefs.dateFormat, System.currentTimeMillis())) {
            val opts = dateFormats.map { "$it  →  ${android.text.format.DateFormat.format(it, System.currentTimeMillis())}" } + "custom…"
            Ui.choice(this, "date format", opts, dateFormats.indexOf(Prefs.dateFormat)) { i ->
                if (i < dateFormats.size) { Prefs.dateFormat = dateFormats[i]; render(true) }
                else Ui.input(this, "custom date pattern", Prefs.dateFormat, "EEE d MMM") { Prefs.dateFormat = it; render(true) }
            }
        }
        toggle("show battery %", null, { Prefs.showBattery }) { Prefs.showBattery = it }
        toggle("show weather", if (Weather.configured()) Prefs.weatherCity else "set a location in weather", { Prefs.showWeather }) { Prefs.showWeather = it }
        toggle("status on one line", "off = date, battery and weather stacked", { Prefs.infoOneLine }) { Prefs.infoOneLine = it }
        toggle("line below clock & status", "a thin line under the whole block", { Prefs.headerLine }) { Prefs.headerLine = it }
        note("tap actions for the clock, date, battery and weather are in gestures.")
    }

    private fun homeApps() {
        val keys = Prefs.homeApps.toMutableList()
        section("on your home screen")
        if (keys.isEmpty()) note("nothing yet. keep it to a few - the ones you actually need.")
        keys.forEachIndexed { i, key ->
            val app = Apps.byKey(key)
            val l = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            l.addView(Ui.tv(this, "${i + 1}. " + (app?.label ?: "(uninstalled)"), 16f, if (i < Prefs.maxHomeApps) Ui.FG else Ui.DIM).apply {
                setPadding(0, Ui.dp(this@SettingsActivity, 10), 0, Ui.dp(this@SettingsActivity, 10))
                if (app != null) { background = Ui.ripple(); setOnClickListener {
                    Ui.input(this@SettingsActivity, "rename", app.label, app.sysLabel) {
                        Prefs.setLabel(app.key, it.trim().takeIf { s -> s.isNotEmpty() && s != app.sysLabel }); Apps.resort(); render(true)
                    }
                } }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            l.addView(Ui.button(this, "↑") { if (i > 0) { keys.add(i - 1, keys.removeAt(i)); Prefs.homeApps = keys; render(true) } })
            l.addView(Ui.button(this, "↓") { if (i < keys.size - 1) { keys.add(i + 1, keys.removeAt(i)); Prefs.homeApps = keys; render(true) } })
            l.addView(Ui.button(this, "x") { keys.removeAt(i); Prefs.homeApps = keys; render(true) })
            box.addView(l)
        }
        if (keys.size > Prefs.maxHomeApps) note("greyed-out apps are over your limit and not shown.")
        row("+ add app", null) {
            Ui.pickApp(this, "add to home", includeHidden = false) {
                if (it.key !in keys) { keys += it.key; Prefs.homeApps = keys; render(true) }
            }
        }
        note("tap a name to rename it. you can also long-press apps on the home screen or in the drawer.")
        section("options")
        slider("max home apps", 1, 15, "", { Prefs.maxHomeApps }) { Prefs.maxHomeApps = it }
        choice("alignment", alignOpts, { Prefs.align }) { Prefs.align = it }
        choice("vertical position", listOf("top", "center", "bottom"), { Prefs.appsVPos }) { Prefs.appsVPos = it }
        sizeSlider("text size", 24, 12, 48, { Prefs.appSize }) { Prefs.appSize = it }
        sizeSlider("spacing", 14, 0, 48, { Prefs.appSpacing }) { Prefs.appSpacing = it }
    }

    private fun drawerPage() {
        section("behaviour")
        toggle("show all apps", "off = drawer stays empty until you type (more intentional)", { Prefs.drawerShowAll }) { Prefs.drawerShowAll = it }
        toggle("open keyboard automatically", null, { Prefs.drawerKeyboard }) { Prefs.drawerKeyboard = it }
        toggle("auto-launch single match", "opens the app as soon as only one matches", { Prefs.autoLaunch }) { Prefs.autoLaunch = it }
        toggle("fuzzy matching", "'yt' finds YouTube, 'gmap' finds Google Maps", { Prefs.fuzzySearch }) { Prefs.fuzzySearch = it }
        toggle("include hidden apps in search", "hidden apps still stay out of the list", { Prefs.searchHidden }) { Prefs.searchHidden = it }
        toggle("web search when nothing matches", "press enter on the keyboard", { Prefs.searchWebFallback }) { Prefs.searchWebFallback = it }
        section("look")
        sizeSlider("text size", 20, 12, 40, { Prefs.drawerSize }) { Prefs.drawerSize = it }
        choice("alignment", alignOpts, { Prefs.drawerAlign }) { Prefs.drawerAlign = it }
        slider("background", 0, 100, "%", { Prefs.getInt("drawerDim", 88) }) { Prefs.setInt("drawerDim", it) }
        note("swipe down at the top of the list, press back or home to close the drawer.\nlong-press an app for: home, rename, hide, limits, info, uninstall.")
    }

    private fun gestures() {
        section("home screen")
        for ((g, label) in Gestures.all) {
            if (g == "tap_clock") section("taps on the status")
            row(label, Actions.describe(Prefs.gesture(g))) {
                Ui.pickAction(this, label, Prefs.gesture(g)) { Prefs.setGesture(g, it); render(true) }
            }
        }
        row("reset gestures to defaults", null) {
            Gestures.all.forEach { Prefs.setGesture(it.first, Gestures.default(it.first)) }; render(true)
        }
        note("'lock screen' and 'recent apps' need the accessibility service (system & permissions).\n'toggle grayscale' needs a one-time adb permission.")
    }

    private fun widgets() {
        section("preview")
        box.addView(layoutPreview())
        section("your widgets")
        val arr = Prefs.widgets
        if (arr.length() == 0) note("none yet.")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val bits = mutableListOf(WidgetHost.positions[WidgetHost.pos(o)])
            if (o.optString("t") == "sys") bits += "height " + pctOfHeight(o.optInt("h", 120))
            else if (o.has("align") && o.optInt("align") >= 0) bits += alignOpts[o.optInt("align")]
            if (WidgetHost.half(o)) bits += "half width"
            if (WidgetHost.line(o)) bits += "line below"
            if (o.optString("k") in OwnWidgets.SCROLLABLE && o.optInt("h", 0) > 0) bits += "height " + pctOfHeight(o.optInt("h")) + ", scrolls"
            when (o.optString("k")) {
                "countdown" -> bits += o.optString("label") + " " + o.optString("date")
                "screentime" -> bits += "top ${o.optInt("top", 3)} apps"
                "agenda" -> bits += "next ${o.optInt("days", 7)} days"
            }
            row("${i + 1}. " + WidgetHost.title(this, o), bits.joinToString(" · ")) { widgetMenu(i, o) }
        }
        row("+ add widget", "with previews") { WidgetPicker.open(this) }
        section("options")
        choice("default spot for new widgets", WidgetHost.positions, { Prefs.widgetDefaultPos }) { Prefs.widgetDefaultPos = it }
        val g = Gestures.all.firstOrNull { Prefs.gesture(it.first) == "widgets" }
        note("widgets placed on the 'widgets page' open with: " + (g?.second ?: "(assign 'widgets page' to a gesture)") +
            "\nminilaunch's own widgets are plain text and only update while you look at them." +
            "\ntwo half-width widgets next to each other in this list share one row.")
    }

    private fun layoutPreview(): View {
        val c = Prefs.textColor
        val dim = (c and 0x00FFFFFF) or (0x88 shl 24)
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@SettingsActivity, 14)
            setPadding(p, p, p, p)
            background = Ui.box(this@SettingsActivity, stroke = 0xFF444444.toInt(),
                fill = (Prefs.dimColor and 0x00FFFFFF) or ((maxOf(Prefs.dim, 60) * 255 / 100) shl 24))
            minimumHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        }
        fun line(t: String, align: Int, size: Float = 11f, color: Int = c, bold: Boolean = false) =
            frame.addView(Ui.tv(this, HomeStyle.case(t), size, color, bold).apply {
                gravity = HomeStyle.gravity(align); setPadding(0, Ui.dp(this@SettingsActivity, 1), 0, Ui.dp(this@SettingsActivity, 1))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val arr = Prefs.widgets
        fun hr() = frame.addView(View(this).apply { setBackgroundColor((c and 0x00FFFFFF) or (0x55 shl 24)) },
            LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, Ui.dp(this, 1))).apply { setMargins(0, Ui.dp(this@SettingsActivity, 3), 0, Ui.dp(this@SettingsActivity, 3)) })
        fun widgetsAt(pos: Int) {
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { WidgetHost.pos(it) == pos }
            var i = 0
            while (i < list.size) {
                val o = list[i]
                if (WidgetHost.half(o) && i + 1 < list.size && WidgetHost.half(list[i + 1])) {
                    val b = list[i + 1]
                    line("▢ " + WidgetHost.title(this, o).take(13) + "  ▢ " + WidgetHost.title(this, b).take(13), 0, 10f, dim)
                    if (WidgetHost.line(o) || WidgetHost.line(b)) hr()
                    i += 2; continue
                }
                line("▢ " + WidgetHost.title(this, o).take(if (WidgetHost.half(o)) 14 else 28),
                    if (o.optString("t") == "sys" && !WidgetHost.half(o)) 1 else WidgetHost.align(o), 10f, dim)
                if (WidgetHost.line(o)) hr()
                i++
            }
        }
        val ha = Prefs.headerAlign
        if (Prefs.showGreeting) line(HomeStyle.greeting(), ha, 9f, dim)
        if (Prefs.showClock) line(android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis()).toString(), ha, 22f, c, Prefs.boldClock)
        val info = listOfNotNull(if (Prefs.showDate) android.text.format.DateFormat.format(Prefs.dateFormat, System.currentTimeMillis()).toString() else null,
            if (Prefs.showBattery) "${getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" else null,
            if (Prefs.showWeather && Weather.configured()) Weather.text().ifEmpty { null } else null)
        if (info.isNotEmpty()) line(info.joinToString(" · "), ha, 9f)
        if (Prefs.headerLine && (Prefs.showGreeting || Prefs.showClock || info.isNotEmpty())) hr()
        widgetsAt(0)
        val spacer = { frame.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)) }
        if (Prefs.appsVPos != 0) spacer()
        widgetsAt(1)
        val apps = Prefs.homeApps.take(Prefs.maxHomeApps).mapNotNull { Apps.byKey(it)?.label }
        if (apps.isEmpty()) line("(home apps)", Prefs.align, 11f, dim)
        apps.take(5).forEach { line(it, Prefs.align, 12f) }
        if (apps.size > 5) line("…", Prefs.align, 11f, dim)
        widgetsAt(2)
        if (Prefs.appsVPos != 2) spacer()
        widgetsAt(3)
        val onPage = (0 until arr.length()).count { WidgetHost.pos(arr.getJSONObject(it)) == WidgetHost.POS_PAGE }
        if (onPage > 0) line("+ $onPage on widgets page", 1, 9f, dim)
        return frame
    }

    private fun widgetMenu(i: Int, o: org.json.JSONObject) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "position: ${WidgetHost.positions[WidgetHost.pos(o)]}" to {
            Ui.choice(this, "where should it go?", WidgetHost.positions, WidgetHost.pos(o)) { p -> WidgetHost.update(i) { put("pos", p) }; render(true) }
        }
        items += "width: " + (if (WidgetHost.half(o)) "half" else "full") to {
            WidgetHost.update(i) { put("half", !WidgetHost.half(o)) }; render(true)
        }
        items += "line below: " + (if (WidgetHost.line(o)) "on" else "off") to {
            WidgetHost.update(i) { put("line", !WidgetHost.line(o)) }; render(true)
        }
        if (o.optString("t") == "own" || WidgetHost.half(o)) items += "alignment" to {
            val opts = listOf("same as home apps") + alignOpts
            Ui.choice(this, "alignment", opts, o.optInt("align", -1) + 1) { p -> WidgetHost.update(i) { put("align", p - 1) }; render(true) }
        }
        if (i > 0) items += "move up" to { WidgetHost.move(i, -1); render(true) }
        if (i < Prefs.widgets.length() - 1) items += "move down" to { WidgetHost.move(i, 1); render(true) }
        if (o.optString("t") == "sys") items += "height" to {
            Ui.slider(this, "height (of screen)", 40, 700, o.optInt("h", 120), fmt = { pctOfHeight(it) }) { h -> WidgetHost.update(i) { put("h", h) }; render(true) }
        }
        if (o.optString("k") in OwnWidgets.SCROLLABLE) {
            val h = o.optInt("h", 0)
            items += "height: " + (if (h > 0) pctOfHeight(h) + " of screen, scrolls" else "auto - fits everything") to {
                Ui.slider(this, "height (0 = auto)", 0, 700, h,
                    fmt = { if (it == 0) "auto" else pctOfHeight(it) + " of screen" }) { n ->
                    WidgetHost.update(i) { put("h", if (n in 1..39) 40 else n) }; render(true)
                }
            }
        }
        when (o.optString("k")) {
            "countdown" -> {
                items += "change label" to { Ui.input(this, "label", o.optString("label")) { s -> WidgetHost.update(i) { put("label", s) }; render(true) } }
                items += "change date" to { Ui.input(this, "date (yyyy-mm-dd)", o.optString("date")) { s -> WidgetHost.update(i) { put("date", s.trim()) }; render(true) } }
            }
            "screentime" -> items += "number of apps listed" to {
                Ui.slider(this, "top apps", 0, 10, o.optInt("top", 3), "") { n -> WidgetHost.update(i) { put("top", n) }; render(true) }
            }
            "agenda", "calendar" -> {
                if (o.optString("k") == "agenda") {
                    val h = o.optInt("h", 0)
                    items += "days ahead: ${o.optInt("days", 7)}" to {
                        Ui.slider(this, "days ahead", 1, 60, o.optInt("days", 7), " days") { n -> WidgetHost.update(i) { put("days", n) }; render(true) }
                    }
                    val maxDef = if (h > 0) 0 else 8
                    val maxV = o.optInt("max", maxDef)
                    items += "max events shown: " + (if (maxV <= 0) "no limit" else "$maxV") to {
                        Ui.slider(this, "max events shown (0 = no limit)", 0, 50, maxV,
                            fmt = { if (it == 0) "no limit" else "$it" }) { n -> WidgetHost.update(i) { put("max", n) }; render(true) }
                    }
                    items += "all-day events: " + (if (o.optBoolean("allday", true)) "shown" else "hidden") to {
                        WidgetHost.update(i) { put("allday", !o.optBoolean("allday", true)) }; render(true)
                    }
                }
                items += "calendars: " + Cal.describe(o) to {
                    Cal.pickCalendars(this, o) { sel -> WidgetHost.update(i) { if (sel == null) remove("cals") else put("cals", sel) }; render(true) }
                }
                items += "open events in: " + Cal.appName(this, o) to {
                    Cal.pickApp(this, o) { pkg -> WidgetHost.update(i) { put("app", pkg) }; render(true) }
                }
            }
            "note" -> items += "edit text" to {
                val uid = o.optString("uid")
                Ui.input(this, "note", Prefs.data("note_$uid"), multiline = true) { Prefs.setData("note_$uid", it); Prefs.widgets = Prefs.widgets; render(true) }
            }
        }
        items += "remove" to { WidgetHost.remove(this, i); render(true) }
        Ui.menu(this, WidgetHost.title(this, o), items)
    }

    private fun weather() {
        section("location")
        row("city", Prefs.weatherCity.ifEmpty { "not set - tap to search" }) {
            Ui.input(this, "search city", "", "e.g. Prague") { q ->
                if (q.isBlank()) return@input
                Weather.geocode(q) { res ->
                    when {
                        res == null -> Ui.info(this, "weather", "Couldn't reach the geocoding service. Check your connection.")
                        res.isEmpty() -> Ui.info(this, "weather", "No place found for \"$q\".")
                        else -> Ui.choice(this, "pick a place", res.map { it.name }, -1) { i ->
                            setPlace(res[i].name, res[i].lat, res[i].lon)
                        }
                    }
                }
            }
        }
        row("use my approximate location", "one-time, from the last known network location - no GPS") { useLocation() }
        section("display")
        toggle("show weather", null, { Prefs.showWeather }) { Prefs.showWeather = it }
        toggle("fahrenheit", null, { Prefs.fahrenheit }) { Prefs.fahrenheit = it; Prefs.clearWeatherCache() }
        val opts = listOf(30, 60, 120, 180, 360)
        choice("refresh at most every", opts.map { if (it < 60) "$it min" else "${it / 60} h" }, { opts.indexOf(Prefs.weatherRefresh).coerceAtLeast(0) }) {
            Prefs.weatherRefresh = opts[it]
        }
        row("refresh now", Weather.text().ifEmpty { "-" }) { Weather.refresh(true) { render(true) } }
        note("weather comes from open-meteo.com (no account, no key). it's only fetched while the home screen is open and the last value is older than the interval above - never in the background.")
    }

    private fun setPlace(name: String, lat: Double, lon: Double) {
        Prefs.weatherCity = name; Prefs.weatherLat = lat.toFloat(); Prefs.weatherLon = lon.toFloat()
        Prefs.clearWeatherCache()
        Weather.refresh(true) { render(true) }
        render(true)
    }

    private fun useLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 11); return
        }
        val lm = getSystemService(LocationManager::class.java)
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (loc == null) { Ui.info(this, "weather", "No recent location is known. Search for your city instead."); return }
        val lat = Math.round(loc.latitude * 100) / 100.0
        val lon = Math.round(loc.longitude * 100) / 100.0
        setPlace("%.2f, %.2f".format(lat, lon), lat, lon)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == 11 && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) useLocation()
        render(true)
    }

    private fun wellbeing() {
        val access = Usage.hasAccess(this)
        section("screen time")
        if (!access) row("! allow usage access", "needed for limits and the screen-time widget") {
            Actions.start(this, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } else {
            val map = Usage.today(this)
            val labels = Apps.all.associate { it.pkg to it.label }
            row("today: ${Usage.fmt(map.values.sum())} · ${Usage.unlocks} unlocks", "tap for the system screen-time view") {
                Actions.runGlobal(this, "screen_time")
            }
            map.entries.filter { it.key in labels }.sortedByDescending { it.value }.take(5).forEach {
                note("  ${labels[it.key]}  ${Usage.fmt(it.value)}" + (Usage.limitMinutes(it.key).takeIf { m -> m > 0 }?.let { m -> " / ${m}m" } ?: ""))
            }
        }
        section("app limits & mindful pause")
        val limits = Prefs.limits
        val keys = limits.keys().asSequence().toList()
        if (keys.isEmpty()) note("no limits set. pick the apps that pull you in.")
        for (pkg in keys) {
            val app = Apps.all.firstOrNull { it.pkg == pkg }
            val m = Usage.limitMinutes(pkg); val d = Usage.delaySeconds(pkg)
            val sub = listOfNotNull(if (m > 0) "${m}m / day" else null, if (d > 0) "${d}s pause" else null).joinToString(" · ")
            row(app?.label ?: pkg, sub) {
                if (app == null) { Usage.setLimit(pkg, 0, 0); render(true); return@row }
                Ui.menu(this, app.label, listOf(
                    "daily limit" to { LimitsUi.editLimit(this, app) { render(true) } },
                    "mindful pause" to { LimitsUi.editDelay(this, app) { render(true) } },
                    "remove" to { Usage.setLimit(pkg, 0, 0); render(true) },
                ))
            }
        }
        row("+ add app", null) {
            Ui.pickApp(this, "limit which app?") { app ->
                Ui.menu(this, app.label, listOf(
                    "daily limit" to { LimitsUi.editLimit(this, app) { render(true) } },
                    "mindful pause" to { LimitsUi.editDelay(this, app) { render(true) } },
                ))
            }
        }
        val every = listOf(1, 2, 5, 10, 15, 30)
        choice("remind again every", every.map { "$it min while over the limit" }, { every.indexOf(Prefs.remindEvery).coerceAtLeast(0) }) {
            Prefs.remindEvery = every[it]
        }
        toggle("strict mode", "over the limit, 'open anyway' unlocks only after 15 s", { Prefs.limitBlocks }) { Prefs.limitBlocks = it }
        note("limits are checked when you open an app from minilaunch. a single low-power alarm then reminds you if you're still in it when the time runs out - no background monitoring.")
        section("display")
        if (Actions.hasSecureSettings(this)) {
            toggle("grayscale screen", "colour makes apps more enticing", { Actions.isGrayscale(this) }) { Actions.setGrayscale(this, it) }
        } else {
            row("grayscale screen", "needs a one-time permission - tap for how") {
                Ui.info(this, "grayscale", "Connect the phone to a computer and run once:\n\nadb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n\nThen you can toggle grayscale here or with a gesture.")
            }
        }
    }

    private fun hidden() {
        val hidden = Prefs.hidden
        section("hidden from the drawer")
        val apps = Apps.all.filter { it.key in hidden }
        if (apps.isEmpty()) note("no hidden apps.")
        for (app in apps) {
            row(app.label, if (app.work) "work profile" else null) {
                Ui.menu(this, app.label, listOf(
                    "open" to { Launcher.launch(this, app) },
                    "unhide" to { Prefs.hidden = Prefs.hidden - app.key; render(true) },
                    "rename" to { Ui.input(this, "rename", app.label, app.sysLabel) {
                        Prefs.setLabel(app.key, it.trim().takeIf { s -> s.isNotEmpty() && s != app.sysLabel }); Apps.resort(); render(true) } },
                ))
            }
        }
        row("+ hide an app", null) {
            Ui.pickApp(this, "hide which app?", includeHidden = false) {
                Prefs.hidden = Prefs.hidden + it.key
                Prefs.homeApps = Prefs.homeApps - it.key
                render(true)
            }
        }
        note("hidden apps can still be opened from here" + (if (Prefs.searchHidden) " or by searching for them." else ". enable 'include hidden apps in search' in the drawer settings to find them by name."))
    }

    private fun system() {
        val isDefault = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
        section("launcher")
        row("default home app", if (isDefault) "minilaunch ✓" else "not default - tap to change") { requestHome() }
        section("recent apps screen")
        RecentsTweaks.rows(this).forEach { (label, sub, fn) -> row(label, sub) { fn(); render(true) } }
        section("permissions")
        row("usage access", if (Usage.hasAccess(this)) "granted ✓" else "not granted - limits & screen time") {
            Actions.start(this, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        val notif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        row("notifications", if (notif) "granted ✓" else "not granted - limit reminders") {
            if (!notif) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
            else Actions.start(this, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        val cal = Cal.granted(this)
        row("calendar", if (cal) "granted ✓" else "not granted - calendar widgets") {
            if (!cal) Cal.ask(this)
            else Actions.start(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        row("accessibility (lock / recents)", if (LockService.enabled) "enabled ✓" else "off - double-tap-to-lock won't work") {
            Actions.start(this, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        row("secure settings (grayscale)", if (Actions.hasSecureSettings(this)) "granted ✓" else "not granted - needs adb") {
            Ui.info(this, "secure settings", "Run once from a computer:\n\nadb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
        }
        row("battery optimisation", "keep minilaunch optimised - it does nothing in the background") {
            Actions.start(this, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        row("app info", null) { Actions.start(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    private fun requestHome() {
        val rm = getSystemService(RoleManager::class.java)
        try { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 5) }
        catch (_: Throwable) { Actions.start(this, Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    private fun backup() {
        section("backup")
        row("export settings to a file", null) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "minilaunch-backup.json"), 21)
        }
        row("import settings from a file", null) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 22)
        }
        section("reset")
        row("reset everything to defaults", "home apps, gestures, widgets, limits…") {
            Ui.confirm(this, "reset?", "All MiniLaunch settings will be erased.", "reset") {
                for (i in 0 until Prefs.widgets.length()) Prefs.widgets.optJSONObject(i)?.takeIf { it.optString("t") == "sys" }
                    ?.let { WidgetHost.host(this).deleteAppWidgetId(it.getInt("id")) }
                Prefs.import("{}"); render()
            }
        }
    }

    @Deprecated("simple results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        WidgetPicker.onResult(this, requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            try {
                when (requestCode) {
                    21 -> contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Prefs.export().toByteArray()) }
                        .also { Ui.info(this, "backup", "Saved.") }
                    22 -> {
                        Prefs.import(contentResolver.openInputStream(uri)!!.bufferedReader().readText())
                        Apps.resort(); Ui.info(this, "backup", "Imported.")
                    }
                }
            } catch (t: Throwable) { Ui.info(this, "backup", "Failed: ${t.message}") }
        }
        render(true)
    }
}
