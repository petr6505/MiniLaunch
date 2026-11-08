package dev.minilaunch

import android.app.Activity
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var dimView: View
    private lateinit var home: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var widgetPage: ScrollView
    private lateinit var widgetPageBox: LinearLayout

    private var batteryView: TextView? = null
    private var weatherView: TextView? = null
    private var appliedVersion = -1
    private var results: List<AppEntry> = emptyList()
    private val interactive = ArrayList<View>()
    private var insets = Rect()
    private var imeBottom = 0

    private val appsChanged: () -> Unit = { if (drawer.visibility == View.VISIBLE) filter(); buildHome() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)

        root = FrameLayout(this)
        dimView = View(this)
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drawer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        widgetPageBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        widgetPage = ScrollView(this).apply {
            visibility = View.GONE; isVerticalScrollBarEnabled = false; addView(widgetPageBox)
            isFillViewport = true
        }
        root.addView(dimView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(home, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(widgetPage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(drawer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, wi ->
            val sb = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            // keep the top inset when the status bar is hidden so the clock doesn't jump
            val top = maxOf(sb.top, wi.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top)
            insets = Rect(sb.left, top, sb.right, sb.bottom)
            imeBottom = wi.getInsets(WindowInsets.Type.ime()).bottom
            applyPadding()
            wi
        }

        buildDrawer()
        Apps.addListener(appsChanged)
        Thread {
            Apps.load(this)
            runOnUiThread { appliedVersion = -1; applyAll(); cascade(home) }
        }.start()

        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
        applyAll()
    }

    private val backCallback = OnBackInvokedCallback { closeOverlays() }

    override fun onDestroy() {
        Apps.removeListener(appsChanged)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        runCatching { WidgetHost.host(this).startListening() }
        if (Cal.granted(this)) runCatching {
            contentResolver.registerContentObserver(android.provider.CalendarContract.CONTENT_URI, true, calObserver)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { WidgetHost.host(this).stopListening() }
        runCatching { contentResolver.unregisterContentObserver(calObserver) }
        closeOverlays(animate = false)
    }

    override fun onResume() {
        super.onResume()
        WidgetPicker.onAdded = { applyIfChanged() }
        if (Prefs.version != appliedVersion) applyAll() else refreshDynamic()
        if (returning) { returning = false; cascade(home, dy = 10f, step = 22, dur = 320) }
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (Prefs.showWeather) Weather.refresh(false) { weatherView?.text = HomeStyle.case(it) }
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(battery) }
        hideKeyboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN == intent.action) closeOverlays()
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (scale > 0) level * 100 / scale else level
            batteryView?.text = HomeStyle.case("$pct%" + if (charging) " charging" else "")
        }
    }

    private fun applyAll() {
        appliedVersion = Prefs.version
        applyWindow()
        dimView.setBackgroundColor(withAlpha(Prefs.dimColor, Prefs.dim))
        buildHome()
        styleDrawer()
        applyPadding()
    }

    // own widgets whose content goes stale (screen time, dates) get re-rendered on resume
    private val refreshers = ArrayList<() -> Unit>()
    private val calRefreshers = ArrayList<() -> Unit>()

    // redraw calendar widgets when events change (sync, edits) while the home screen is up
    private val calObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        private val redraw = Runnable { calRefreshers.forEach { it() } }
        override fun onChange(selfChange: Boolean) {
            home.removeCallbacks(redraw); home.postDelayed(redraw, 800)
        }
    }

    private fun refreshDynamic() {
        refreshers.forEach { it() }
        greetingView?.text = HomeStyle.case(greeting())
        weatherView?.text = HomeStyle.case(Weather.text())
    }

    private fun withAlpha(c: Int, pct: Int) = (c and 0x00FFFFFF) or ((pct.coerceIn(0, 100) * 255 / 100) shl 24)

    private fun applyWindow() {
        val c = window.insetsController ?: return
        if (Prefs.showStatusBar) c.show(WindowInsets.Type.statusBars())
        else {
            c.hide(WindowInsets.Type.statusBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val lightText = Color.luminance(Prefs.textColor) > 0.5f
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        c.setSystemBarsAppearance(if (lightText) 0 else mask, mask)
    }

    private fun applyPadding() {
        val side = Ui.dp(this, Prefs.sidePadding)
        val v = Ui.dp(this, 24)
        home.setPadding(insets.left + side, insets.top + v, insets.right + side, insets.bottom + v)
        widgetPageBox.setPadding(insets.left + side, insets.top + v, insets.right + side, insets.bottom + v)
        drawer.setPadding(insets.left + side, insets.top + Ui.dp(this, 16), insets.right + side,
            maxOf(insets.bottom, imeBottom) + Ui.dp(this, 8))
    }

    private fun homeText(s: String, size: Int, dim: Boolean = false): TextView =
        Ui.tv(this, HomeStyle.case(s), size.toFloat(), if (dim) dimText() else Prefs.textColor).apply {
            setShadowLayer(if (Prefs.dim < 30) 6f else 0f, 0f, 1f, 0x88000000.toInt())
        }

    private fun dimText() = (Prefs.textColor and 0x00FFFFFF) or (0x99 shl 24)

    private fun buildHome() {
        home.removeAllViews()
        interactive.clear()
        refreshers.clear()
        calRefreshers.clear()
        widgetBoxes.clear()
        buildWidgetPage()
        home.gravity = HomeStyle.gravity(Prefs.headerAlign)

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = HomeStyle.gravity(Prefs.headerAlign)
        }
        greetingView = null
        if (Prefs.showGreeting) {
            val g = homeText(greeting(), Prefs.infoSize, dim = true).apply {
                setPadding(0, 0, 0, Ui.dp(this@MainActivity, 2))
                letterSpacing = 0.04f
            }
            greetingView = g
            header.addView(g)
        }
        if (Prefs.showClock) {
            val clock = TextClock(this).apply {
                val fmt24 = if (Prefs.showSeconds) "HH:mm:ss" else "HH:mm"
                val fmt12 = if (Prefs.showSeconds) "h:mm:ss" else "h:mm"
                when (Prefs.clockMode) {
                    1 -> { format24Hour = fmt24; format12Hour = fmt24 }
                    2 -> { format24Hour = fmt12; format12Hour = fmt12 }
                    else -> { format24Hour = fmt24; format12Hour = fmt12 }
                }
                typeface = if (Prefs.boldClock) android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    else android.graphics.Typeface.MONOSPACE
                setTextColor(Prefs.textColor)
                HomeStyle.sp(this, Prefs.clockSize)
                includeFontPadding = false
                setShadowLayer(if (Prefs.dim < 30) 6f else 0f, 0f, 1f, 0x88000000.toInt())
            }
            tap(clock, "tap_clock")
            header.addView(clock)
        }
        val info = LinearLayout(this).apply {
            orientation = if (Prefs.infoOneLine) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = HomeStyle.gravity(Prefs.headerAlign)
            setPadding(0, Ui.dp(this@MainActivity, 6), 0, 0)
        }
        fun addInfo(v: TextView) {
            if (Prefs.infoOneLine && info.childCount > 0) info.addView(homeText(" · ", Prefs.infoSize, dim = true))
            info.addView(v)
        }
        if (Prefs.showDate) {
            val date = TextClock(this).apply {
                format12Hour = Prefs.dateFormat; format24Hour = Prefs.dateFormat
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Prefs.textColor)
                HomeStyle.sp(this, Prefs.infoSize)
                isAllCaps = Prefs.textCase == 2
                setShadowLayer(if (Prefs.dim < 30) 6f else 0f, 0f, 1f, 0x88000000.toInt())
            }
            if (Prefs.textCase == 1) date.transformationMethod = LowerCase
            tap(date, "tap_date"); addInfo(date)
        }
        batteryView = null; weatherView = null
        if (Prefs.showBattery) {
            val b = homeText("", Prefs.infoSize)
            batteryView = b; tap(b, "tap_battery"); addInfo(b)
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { battery.onReceive(this, it) }
        }
        if (Prefs.showWeather && Weather.configured()) {
            val w = homeText(Weather.text().ifEmpty { "…" }, Prefs.infoSize)
            weatherView = w; tap(w, "tap_weather"); addInfo(w)
        }
        if (info.childCount > 0) header.addView(info)
        if (header.childCount > 0 && Prefs.headerLine) header.addView(divider(), dividerLp().apply { topMargin = Ui.dp(this@MainActivity, 12) })
        if (header.childCount > 0) home.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // widgets under header
        val widthPx = contentWidth()
        addWidgets(home, widthPx, Ui.dp(this, 16), 0)

        // apps
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = when (Prefs.appsVPos) { 0 -> Gravity.TOP; 2 -> Gravity.BOTTOM; else -> Gravity.CENTER_VERTICAL } or
                HomeStyle.gravity(Prefs.align)
        }
        addWidgets(middle, widthPx, Ui.dp(this, 12), 1, bottomGap = Ui.dp(this, 12))
        val keys = Prefs.homeApps.take(Prefs.maxHomeApps)
        val apps = keys.mapNotNull { Apps.byKey(it) }
        if (apps.isEmpty() && Apps.all.isNotEmpty() && keys.isEmpty()) {
            middle.addView(homeText("swipe up for apps\nlong-press to set up", Prefs.infoSize, dim = true).apply {
                gravity = HomeStyle.gravity(Prefs.align)
            })
        }
        for (app in apps) {
            val t = homeText(app.label, Prefs.appSize).apply {
                val p = Ui.dp(this@MainActivity, Prefs.appSpacing / 2f)
                setPadding(0, p, 0, p)
                gravity = HomeStyle.gravity(Prefs.align)
                setOnClickListener { v -> Launcher.launch(this@MainActivity, app, bounds(v)) }
                setOnLongClickListener { appMenu(app); true }
                pressFeedback(this)
            }
            interactive += t
            middle.addView(t, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        addWidgets(middle, widthPx, Ui.dp(this, 16), 2)
        home.addView(middle, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        addWidgets(home, widthPx, Ui.dp(this, 12), 3)
    }

    private fun contentWidth() =
        resources.displayMetrics.widthPixels - insets.left - insets.right - 2 * Ui.dp(this, Prefs.sidePadding)

    private var greetingView: TextView? = null

    private fun greeting() = HomeStyle.greeting()

    private val ease = android.view.animation.DecelerateInterpolator(2f)
    private val motion get() = Prefs.motion

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun pressFeedback(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().alpha(0.45f).scaleX(0.985f).scaleY(0.985f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(ease).start()
            }
            false
        }
    }

    private fun cascade(group: ViewGroup, dy: Float = 14f, step: Long = 28, dur: Long = 360) {
        if (!motion) return
        val d = Ui.dp(this, dy).toFloat()
        var i = 0
        fun walk(g: ViewGroup) {
            for (c in 0 until g.childCount) {
                val v = g.getChildAt(c)
                if (v.visibility != View.VISIBLE) continue
                if (v is LinearLayout && v.orientation == LinearLayout.VERTICAL && v.childCount in 1..12 && v !is android.appwidget.AppWidgetHostView) {
                    walk(v); continue
                }
                v.animate().cancel()
                v.alpha = 0f; v.translationY = d
                v.animate().alpha(1f).translationY(0f).setStartDelay(i * step).setDuration(dur).setInterpolator(ease).start()
                i++
            }
        }
        walk(group)
    }

    private object LowerCase : android.text.method.TransformationMethod {
        override fun getTransformation(source: CharSequence?, view: View?) = source?.toString()?.lowercase()
        override fun onFocusChanged(v: View?, s: CharSequence?, f: Boolean, d: Int, r: Rect?) {}
    }

    // half-width widgets that follow each other share a row
    private fun addWidgets(parent: LinearLayout, widthPx: Int, gap: Int, pos: Int, bottomGap: Int = 0) {
        val arr = Prefs.widgets
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { WidgetHost.pos(it) == pos }
        val split = Ui.dp(this, 14)
        val halfPx = (widthPx - split) / 2
        var i = 0
        while (i < list.size) {
            val o = list[i]
            val row: View
            if (WidgetHost.half(o)) {
                val members = if (i + 1 < list.size && WidgetHost.half(list[i + 1])) listOf(o, list[i + 1]) else listOf(o)
                i += members.size
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (members.size == 2) Gravity.START else HomeStyle.gravity(WidgetHost.align(o))
                }
                for (m in members) {
                    val cell = widgetCell(m, halfPx) ?: continue
                    val lp = LinearLayout.LayoutParams(halfPx, MATCH_PARENT)
                    if (r.childCount > 0) lp.marginStart = split
                    r.addView(cell, lp)
                }
                if (r.childCount == 0) continue
                row = r
            } else {
                i++
                row = widgetCell(o, widthPx) ?: continue
            }
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.topMargin = gap
            lp.bottomMargin = bottomGap
            parent.addView(row, lp)
        }
    }

    // the widget itself, plus its optional line underneath (pushed to the bottom so paired lines align)
    private fun widgetCell(o: org.json.JSONObject, widthPx: Int): View? {
        val v: View = if (o.optString("t") == "own") {
            val box = FrameLayout(this)
            lateinit var render: () -> Unit
            render = {
                // keep the scroll position when a scrolling widget redraws
                val y = (box.getChildAt(0) as? ScrollView)?.scrollY ?: 0
                box.removeAllViews(); box.addView(OwnWidgets.view(this, o) { render() })
                (box.getChildAt(0) as? ScrollView)?.let { sv -> if (y > 0) sv.post { sv.scrollTo(0, y) } }
            }
            render()
            widgetBoxes += box
            val k = o.optString("k")
            if (k in setOf("screentime", "calendar", "prompt", "countdown", "agenda")) refreshers += render
            if (k in setOf("calendar", "agenda")) calRefreshers += render
            box
        } else WidgetHost.view(this, o, widthPx) {} ?: return null
        (v.parent as? ViewGroup)?.removeView(v)
        interactive += v
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val vlp = (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        vlp.width = MATCH_PARENT
        col.addView(v, vlp)
        if (WidgetHost.line(o)) {
            col.addView(android.widget.Space(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            col.addView(divider(), dividerLp())
        }
        return col
    }

    private fun divider() = View(this).apply { setBackgroundColor((Prefs.textColor and 0x00FFFFFF) or (0x44 shl 24)) }
    private fun dividerLp() = LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, Ui.dp(this, 1))).apply { topMargin = Ui.dp(this@MainActivity, 10) }

    private fun buildWidgetPage() {
        widgetPageBox.removeAllViews()
        widgetPageBox.gravity = Gravity.CENTER_VERTICAL
        addWidgets(widgetPageBox, contentWidth(), Ui.dp(this, 18), WidgetHost.POS_PAGE)
        if (widgetPageBox.childCount == 0) {
            widgetPageBox.addView(homeText("no widgets yet\nlong-press → add widget", Prefs.infoSize, dim = true).apply {
                gravity = HomeStyle.gravity(Prefs.align)
                setOnLongClickListener { WidgetPicker.open(this@MainActivity); true }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun bounds(v: View): Rect {
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    private fun tap(v: View, gesture: String) {
        interactive += v
        v.setOnClickListener { runAction(Prefs.gesture(gesture)) }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(p: Int) = results[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
            val t = (v as? TextView) ?: Ui.tv(this@MainActivity).apply {
                val pad = Ui.dp(this@MainActivity, 10); setPadding(0, pad, 0, pad)
            }
            val app = results[p]
            t.setTextColor(Prefs.textColor)
            HomeStyle.sp(t, Prefs.drawerSize)
            t.gravity = HomeStyle.gravity(Prefs.drawerAlign)
            val hidden = app.key in Prefs.hidden
            t.text = HomeStyle.case(app.label) + (if (app.work) " *" else "") + (if (hidden) " (hidden)" else "")
            t.alpha = if (hidden) 0.5f else 1f
            return t
        }
    }

    private fun buildDrawer() {
        search = Ui.edit(this, "", "search").apply {
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { launchFirst(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { filter(); maybeAutoLaunch() }
                override fun beforeTextChanged(c: CharSequence?, s: Int, b: Int, n: Int) {}
                override fun onTextChanged(c: CharSequence?, s: Int, b: Int, n: Int) {}
            })
        }
        list = ListView(this).apply {
            divider = null
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, v, p, _ -> results.getOrNull(p)?.let { Launcher.launch(this@MainActivity, it, bounds(v)) } }
            setOnItemLongClickListener { _, _, p, _ -> results.getOrNull(p)?.let { appMenu(it) }; true }
            // fling down at the top closes the drawer
            val det = GestureDetector(this@MainActivity, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    if (e1 != null && vy > 1500 && abs(vy) > abs(vx) && !canScrollVertically(-1)) { closeOverlays(); return true }
                    return false
                }
            })
            setOnTouchListener { _, e -> det.onTouchEvent(e); false }
        }
        list.layoutAnimation = android.view.animation.LayoutAnimationController(
            android.view.animation.AnimationSet(true).apply {
                addAnimation(android.view.animation.AlphaAnimation(0f, 1f))
                addAnimation(android.view.animation.TranslateAnimation(0f, 0f, Ui.dp(this@MainActivity, 18).toFloat(), 0f))
                duration = 300; interpolator = ease
            }, 0.06f)
        drawer.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        drawer.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = Ui.dp(this@MainActivity, 8) })
        drawer.setOnClickListener { closeOverlays() }
    }

    private fun styleDrawer() {
        drawer.setBackgroundColor(withAlpha(Prefs.dimColor, Prefs.getInt("drawerDim", 88)))
        search.setTextColor(Prefs.textColor)
        HomeStyle.sp(search, Prefs.drawerSize)
        search.gravity = HomeStyle.gravity(Prefs.drawerAlign)
        search.hint = HomeStyle.case(if (Prefs.drawerShowAll) "search" else "type to search")
        adapter.notifyDataSetChanged()
    }

    private fun filter() {
        val q = search.text.toString()
        results = if (q.isBlank() && !Prefs.drawerShowAll) emptyList()
        else Apps.search(q, Prefs.searchHidden && q.isNotBlank(), Prefs.fuzzySearch)
        adapter.notifyDataSetChanged()
        list.setSelection(0)
    }

    private fun maybeAutoLaunch() {
        if (!Prefs.autoLaunch || search.text.isBlank()) return
        if (results.size == 1) Launcher.launch(this, results[0], null)
    }

    private fun launchFirst() {
        val q = search.text.toString().trim()
        val first = results.firstOrNull()
        when {
            first != null && q.isNotEmpty() -> Launcher.launch(this, first, null)
            q.isNotEmpty() && Prefs.searchWebFallback ->
                Actions.start(this, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
        }
    }

    private fun show(v: View, fromX: Float = 0f, fromY: Float = 0f, fromScale: Float = 1f) {
        v.animate().cancel()
        v.visibility = View.VISIBLE
        if (!motion) { v.alpha = 1f; v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; return }
        v.alpha = 0f; v.translationX = fromX; v.translationY = fromY; v.scaleX = fromScale; v.scaleY = fromScale
        v.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(0).setDuration(280).setInterpolator(ease).start()
    }

    private fun hide(v: View, toX: Float = 0f, toY: Float = 0f, gone: Boolean = true, animate: Boolean = true) {
        if (v.visibility != View.VISIBLE) return
        v.animate().cancel()
        val end = { v.visibility = if (gone) View.GONE else View.INVISIBLE; v.alpha = 1f; v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f }
        if (!motion || !animate) { end(); return }
        v.animate().alpha(0f).translationX(toX).translationY(toY).setStartDelay(0).setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction(end).start()
    }

    private fun openDrawer(keyboard: Boolean) {
        hide(widgetPage, animate = false)
        search.setText("")
        filter()
        show(drawer, fromY = Ui.dp(this, 48).toFloat())
        hide(home, toY = -Ui.dp(this, 24).toFloat(), gone = false)
        if (motion) list.scheduleLayoutAnimation()
        if (keyboard) {
            search.requestFocus()
            search.post { getSystemService(InputMethodManager::class.java).showSoftInput(search, 0) }
        } else {
            search.clearFocus()
            list.requestFocus()
        }
    }

    private fun openWidgetPage() {
        drawer.visibility = View.GONE
        show(widgetPage, fromX = Ui.dp(this, 40).toFloat() * (if (lastSwipe == "swipe_right") -1 else 1))
        hide(home)
        cascade(widgetPageBox)
    }

    private fun closeOverlays(animate: Boolean = true) {
        hideKeyboard()
        if (::search.isInitialized && search.text.isNotEmpty()) search.setText("")
        val wasOpen = drawer.visibility == View.VISIBLE || widgetPage.visibility == View.VISIBLE
        val wasPage = widgetPage.visibility == View.VISIBLE
        hide(drawer, toY = Ui.dp(this, 40).toFloat(), animate = animate)
        hide(widgetPage, toX = Ui.dp(this, 40).toFloat() * (if (lastSwipe == "swipe_right") -1 else 1), animate = animate)
        if (!wasOpen) return
        if (animate) show(home, fromY = if (wasPage) 0f else -Ui.dp(this, 16).toFloat(), fromScale = 0.985f)
        else { home.animate().cancel(); home.visibility = View.VISIBLE; home.alpha = 1f; home.translationY = 0f; home.scaleX = 1f; home.scaleY = 1f }
    }

    private var lastSwipe = ""
    private var returning = false

    override fun onRestart() { super.onRestart(); returning = true }

    private fun hideKeyboard() {
        if (!::search.isInitialized) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun appMenu(app: AppEntry) {
        val homeApps = Prefs.homeApps.toMutableList()
        val onHome = app.key in homeApps
        val hidden = app.key in Prefs.hidden
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (onHome) {
            val i = homeApps.indexOf(app.key)
            items += "remove from home" to { homeApps.remove(app.key); Prefs.homeApps = homeApps; applyIfChanged() }
            if (i > 0) items += "move up" to { homeApps.removeAt(i); homeApps.add(i - 1, app.key); Prefs.homeApps = homeApps; applyIfChanged() }
            if (i < homeApps.size - 1) items += "move down" to { homeApps.removeAt(i); homeApps.add(i + 1, app.key); Prefs.homeApps = homeApps; applyIfChanged() }
        } else {
            items += "add to home" to {
                if (homeApps.size >= Prefs.maxHomeApps) Ui.info(this, "home is full",
                    "You have ${Prefs.maxHomeApps} home apps. Remove one, or raise the limit in settings → home apps.\n\nFewer is better :)")
                else { homeApps += app.key; Prefs.homeApps = homeApps; applyIfChanged() }
            }
        }
        items += "rename" to {
            Ui.input(this, "rename", app.label, app.sysLabel) {
                Prefs.setLabel(app.key, it.trim().takeIf { s -> s.isNotEmpty() && s != app.sysLabel })
                Apps.resort(); applyIfChanged(); filter()
            }
        }
        items += (if (hidden) "unhide" else "hide") to {
            Prefs.hidden = if (hidden) Prefs.hidden - app.key else Prefs.hidden + app.key
            if (!hidden) { homeApps.remove(app.key); Prefs.homeApps = homeApps }
            applyIfChanged(); filter()
        }
        val lim = Usage.limitMinutes(app.pkg)
        items += (if (lim > 0) "daily limit: ${lim}m" else "set daily limit") to { LimitsUi.editLimit(this, app) { applyIfChanged() } }
        val del = Usage.delaySeconds(app.pkg)
        items += (if (del > 0) "mindful pause: ${del}s" else "set mindful pause") to { LimitsUi.editDelay(this, app) { applyIfChanged() } }
        items += "app info" to { Actions.appInfo(this, app) }
        items += "uninstall" to { Actions.uninstall(this, app) }
        Ui.menu(this, app.label + if (app.work) "  (work)" else "", items)
    }

    private fun applyIfChanged() { if (Prefs.version != appliedVersion) applyAll() }

    private fun launcherMenu() {
        Ui.menu(this, null, listOf(
            "settings" to { runAction("settings") },
            "add widget" to { WidgetPicker.open(this) },
            "home apps" to { startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "home")) },
            "hidden apps" to { startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "hidden")) },
            "wallpaper" to { Actions.start(this, Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "wallpaper")) },
        ))
    }

    private fun runAction(action: String) {
        when {
            action.startsWith("app:") -> Apps.byKey(action.removePrefix("app:"))?.let { Launcher.launch(this, it, null) }
            action == "drawer" -> openDrawer(Prefs.drawerKeyboard)
            action == "search" -> openDrawer(true)
            action == "widgets" -> openWidgetPage()
            action == "menu" -> launcherMenu()
            action == "weather_refresh" -> {
                if (!Weather.configured()) startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "weather"))
                else { weatherView?.text = "…"; Weather.refresh(true) { weatherView?.text = HomeStyle.case(it) } }
            }
            else -> Actions.runGlobal(this, action)
        }
    }

    private var downOnInteractive = false
    private val widgetBoxes = ArrayList<FrameLayout>()
    // a widget that scrolls its content, if the current touch started on one
    private var downOnScroller: ScrollView? = null
    private var flung = false

    private val detector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val min = Ui.dp(this@MainActivity, 60)
                val g = when {
                    abs(dx) > abs(dy) && abs(dx) > min && abs(vx) > 600 -> if (dx > 0) "swipe_right" else "swipe_left"
                    abs(dy) > abs(dx) && abs(dy) > min && abs(vy) > 600 -> if (dy > 0) "swipe_down" else "swipe_up"
                    else -> return false
                }
                // vertical swipes on a scrolling widget belong to the widget
                val sv = downOnScroller
                if (sv != null && (g == "swipe_up" || g == "swipe_down") && (sv.canScrollVertically(1) || sv.canScrollVertically(-1))) return false
                if (widgetPage.visibility == View.VISIBLE) {
                    // reverse of whatever gesture opened the page closes it
                    val opened = Gestures.all.map { it.first }.firstOrNull { Prefs.gesture(it) == "widgets" }
                    val atTop = !widgetPage.canScrollVertically(-1)
                    val atBottom = !widgetPage.canScrollVertically(1)
                    val back = opposite(opened) ?: "swipe_up"
                    val allowed = when (back) { "swipe_down" -> atTop; "swipe_up" -> atBottom; else -> true }
                    if (g == back && allowed) { closeOverlays(); flung = true; return true }
                    return false
                }
                flung = true
                lastSwipe = g
                runAction(Prefs.gesture(g))
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (downOnInteractive || widgetPage.visibility == View.VISIBLE) return false
                runAction(Prefs.gesture("double_tap")); return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (downOnInteractive) return
                if (widgetPage.visibility == View.VISIBLE) { WidgetPicker.open(this@MainActivity); return }
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                runAction(Prefs.gesture("long_press"))
            }
        })
    }

    private fun opposite(g: String?) = when (g) {
        "swipe_left" -> "swipe_right"; "swipe_right" -> "swipe_left"
        "swipe_up" -> "swipe_down"; "swipe_down" -> "swipe_up"; else -> null
    }

    private fun hit(views: List<View>, x: Float, y: Float): Boolean {
        val r = Rect()
        return views.any { it.isShown && it.getGlobalVisibleRect(r) && r.contains(x.toInt(), y.toInt()) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (drawer.visibility == View.VISIBLE) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            flung = false
            downOnInteractive = hit(interactive, ev.rawX, ev.rawY)
            downOnScroller = widgetBoxes.firstOrNull { hit(listOf(it), ev.rawX, ev.rawY) }?.getChildAt(0) as? ScrollView
        }
        detector.onTouchEvent(ev)
        if (flung) {
            // swallow the tap the child under the finger would otherwise get
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel); cancel.recycle()
            if (ev.actionMasked == MotionEvent.ACTION_UP) flung = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("pre-33 fallback")
    override fun onBackPressed() { closeOverlays() }

    @Deprecated("handled for widget bind/config")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        WidgetPicker.onResult(this, requestCode, resultCode, data)
        applyIfChanged()
    }
}
