package dev.minilaunch

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.SizeF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

object WidgetHost {
    const val HOST_ID = 0x4D4C // "ML"

    val positions = listOf("under the clock", "above the apps", "below the apps", "bottom of the screen", "widgets page")
    const val POS_PAGE = 4
    fun pos(o: JSONObject) = o.optInt("pos", 0).coerceIn(0, POS_PAGE)
    // -1 = same as home apps
    fun align(o: JSONObject) = o.optInt("align", -1).let { if (it < 0) Prefs.align else it }
    fun half(o: JSONObject) = o.optBoolean("half")
    fun line(o: JSONObject) = o.optBoolean("line")
    private var host: AppWidgetHost? = null

    fun host(ctx: Context): AppWidgetHost = host ?: AppWidgetHost(ctx.applicationContext, HOST_ID).also { host = it }
    fun manager(ctx: Context): AppWidgetManager = AppWidgetManager.getInstance(ctx)

    fun add(entry: JSONObject) {
        if (!entry.has("pos")) entry.put("pos", Prefs.widgetDefaultPos)
        val a = Prefs.widgets; a.put(entry); Prefs.widgets = a
    }

    fun remove(ctx: Context, index: Int) {
        val a = Prefs.widgets
        val o = a.optJSONObject(index) ?: return
        if (o.optString("t") == "sys") host(ctx).deleteAppWidgetId(o.getInt("id"))
        a.remove(index); Prefs.widgets = a
    }

    fun move(index: Int, delta: Int) {
        val a = Prefs.widgets
        val j = index + delta
        if (j !in 0 until a.length()) return
        val tmp = a.get(index); a.put(index, a.get(j)); a.put(j, tmp)
        Prefs.widgets = a
    }

    fun update(index: Int, block: JSONObject.() -> Unit) {
        val a = Prefs.widgets
        a.optJSONObject(index)?.block()
        Prefs.widgets = a
    }

    fun title(ctx: Context, o: JSONObject): String = when (o.optString("t")) {
        "sys" -> {
            val info = manager(ctx).getAppWidgetInfo(o.optInt("id"))
            if (info == null) "(missing widget)" else "${Names.app(ctx, info.provider.packageName)} - ${info.loadLabel(ctx.packageManager)}"
        }
        else -> OwnWidgets.types.firstOrNull { it.first == o.optString("k") }?.second ?: "widget"
    }

    fun view(a: Activity, o: JSONObject, widthPx: Int, onChanged: () -> Unit): View? = when (o.optString("t")) {
        "sys" -> {
            val id = o.optInt("id")
            val info: AppWidgetProviderInfo? = manager(a).getAppWidgetInfo(id)
            if (info == null) null else {
                val v = host(a).createView(a, id, info)
                val density = a.resources.displayMetrics.density
                val hDp = o.optInt("h", 0).takeIf { it > 0 } ?: (info.minHeight / density).toInt().coerceAtLeast(40)
                val wDp = widthPx / density
                runCatching { v.updateAppWidgetSize(Bundle(), listOf(SizeF(wDp, hDp.toFloat()))) }
                v.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, Ui.dp(a, hDp))
                v.setPadding(0, 0, 0, 0)
                v
            }
        }
        "own" -> OwnWidgets.view(a, o, onChanged)
        else -> null
    }
}

object OwnWidgets {
    val types = listOf(
        "screentime" to "screen time today",
        "todo" to "to-do list",
        "note" to "note / intention",
        "countdown" to "countdown",
        "prompt" to "daily prompt",
        "agenda" to "calendar events",
        "calendar" to "month calendar",
    )

    fun sample(type: String): String = when (type) {
        "screentime" -> "screen time 1h 42m · 23 unlocks\n  messages  31m\n  browser   18m"
        "todo" -> "[ ] call mum\n[x] water the plants\n+ add"
        "note" -> "read before you scroll."
        "countdown" -> "12 days until holiday"
        "prompt" -> "what did you pick up the phone for?"
        "agenda" -> "today\n  09:00   team meeting\n  all day trip to the hills\ntomorrow\n  18:30   dinner with friends"
        "calendar" -> "november 2026\nmo tu we th fr sa su\n                   1\n 2  3  4  5  6  7  8"
        else -> ""
    }

    // widgets that can get a fixed height and scroll inside it
    val SCROLLABLE = setOf("agenda", "todo")

    fun describe(type: String): String = when (type) {
        "agenda" -> "upcoming events from samsung, google, outlook… calendars"
        "calendar" -> "this month · days with events stand out"
        else -> "text only · updates when visible"
    }

    fun create(type: String): JSONObject = JSONObject().put("t", "own").put("k", type).put("uid", UUID.randomUUID().toString().take(8))

    private fun color() = Prefs.textColor
    private fun dimColor(): Int = (Prefs.textColor and 0x00FFFFFF) or (0x99 shl 24)

    private var curAlign = 0
    private fun text(a: Activity, s: CharSequence, scale: Float = 1f, dim: Boolean = false): TextView =
        Ui.tv(a, s, Prefs.infoSize * scale, if (dim) dimColor() else color()).apply {
            gravity = HomeStyle.gravity(curAlign)
            setLineSpacing(0f, 1.15f)
        }

    fun view(a: Activity, o: JSONObject, onChanged: () -> Unit): View {
        curAlign = WidgetHost.align(o)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = HomeStyle.gravity(curAlign)
            setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6))
        }
        val uid = o.optString("uid")
        when (o.optString("k")) {
            "screentime" -> screenTime(a, box, o)
            "todo" -> todo(a, box, uid)
            "note" -> {
                val t = Prefs.data("note_$uid").ifEmpty { "tap to write an intention for today" }
                val v = text(a, HomeStyle.case(t), 1.1f, Prefs.data("note_$uid").isEmpty())
                v.setOnClickListener {
                    Ui.input(a, "note", Prefs.data("note_$uid"), "what matters today?", multiline = true) {
                        Prefs.setData("note_$uid", it); onChanged()
                    }
                }
                box.addView(v)
            }
            "countdown" -> {
                val label = o.optString("label", "")
                val date = o.optString("date", "")
                val days = runCatching {
                    val d = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(date)!!
                    val today = Usage.startOfDay()
                    TimeUnit.MILLISECONDS.toDays(d.time - today + 3_600_000)
                }.getOrNull()
                val s = when {
                    days == null -> "countdown: set a date in settings → widgets"
                    days > 1 -> "$days days until $label"
                    days == 1L -> "tomorrow: $label"
                    days == 0L -> "today: $label"
                    else -> "${-days} days since $label"
                }
                box.addView(text(a, HomeStyle.case(s)))
            }
            "prompt" -> {
                val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
                box.addView(text(a, HomeStyle.case(PROMPTS[day % PROMPTS.size]), 1f, dim = true))
            }
            "agenda" -> agenda(a, box, o)
            "calendar" -> calendar(a, box, o)
        }
        // fixed height: the content scrolls inside it, fading out at the edges
        val h = o.optInt("h", 0)
        if (o.optString("k") in SCROLLABLE && h > 0) return android.widget.ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(Ui.dp(a, 28))
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(box)
            layoutParams = android.widget.FrameLayout.LayoutParams(MATCH_PARENT, Ui.dp(a, h))
        }
        return box
    }

    private val main = Handler(Looper.getMainLooper())

    private fun screenTime(a: Activity, box: LinearLayout, o: JSONObject) {
        if (!Usage.hasAccess(a)) {
            box.addView(text(a, HomeStyle.case("screen time: tap to allow usage access"), 1f, true).apply {
                setOnClickListener { Actions.start(a, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            })
            return
        }
        val head = text(a, HomeStyle.case("screen time …"))
        val list = text(a, "", 0.9f, dim = true)
        box.addView(head); box.addView(list)
        val top = o.optInt("top", 3)
        Thread {
            val map = Usage.today(a)
            val total = map.values.sum()
            val labels = Apps.all.associate { it.pkg to it.label }
            val lines = map.entries.filter { it.key in labels && it.value >= 60_000 }
                .sortedByDescending { it.value }.take(top)
                .joinToString("\n") { "${labels[it.key]}  ${Usage.fmt(it.value)}" }
            val unl = Usage.unlocks
            main.post {
                head.text = HomeStyle.case("screen time ${Usage.fmt(total)} · $unl unlocks")
                list.text = HomeStyle.case(lines)
                list.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            }
        }.start()
        box.setOnClickListener { Actions.runGlobal(a, "screen_time") }
    }

    private fun todo(a: Activity, box: LinearLayout, uid: String) {
        val key = "todo_$uid"
        val items = runCatching { JSONArray(Prefs.data(key)) }.getOrDefault(JSONArray())
        fun save() { Prefs.setData(key, items.toString()) }
        val myAlign = curAlign
        fun redraw() { curAlign = myAlign; box.removeAllViews(); todo(a, box, uid) }
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val done = item.optBoolean("d")
            val v = text(a, (if (done) "[x] " else "[ ] ") + HomeStyle.case(item.optString("t")), 1f, done)
            if (done) v.paintFlags = v.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            v.setPadding(0, Ui.dp(a, 3), 0, Ui.dp(a, 3))
            v.setOnClickListener { item.put("d", !done); save(); redraw() }
            v.setOnLongClickListener {
                Ui.menu(a, item.optString("t"), listOf(
                    "edit" to { Ui.input(a, "edit", item.optString("t")) { s -> item.put("t", s); save(); redraw() } },
                    "delete" to { items.remove(i); save(); redraw() },
                    "clear all done" to {
                        val keep = JSONArray()
                        for (j in 0 until items.length()) if (!items.getJSONObject(j).optBoolean("d")) keep.put(items.get(j))
                        Prefs.setData(key, keep.toString()); redraw()
                    },
                ))
                true
            }
            box.addView(v)
        }
        box.addView(text(a, "+ add", 0.9f, dim = true).apply {
            setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4))
            setOnClickListener {
                Ui.input(a, "new to-do", "", "one thing") { s ->
                    if (s.isNotBlank()) { items.put(JSONObject().put("t", s.trim()).put("d", false)); save(); redraw() }
                }
            }
        })
    }

    private fun calendar(a: Activity, box: LinearLayout, o: JSONObject) {
        val c = Calendar.getInstance()
        val today = c.get(Calendar.DAY_OF_MONTH)
        val title = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(c.time)
        c.set(Calendar.DAY_OF_MONTH, 1)
        val first = (c.get(Calendar.DAY_OF_WEEK) - c.firstDayOfWeek + 7) % 7
        val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val names = (0 until 7).map {
            val d = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, (c.firstDayOfWeek + it - 1) % 7 + 1) }
            SimpleDateFormat("EE", Locale.getDefault()).format(d.time).take(2)
        }
        // days with events stay at full brightness, the rest are dimmed
        val marked: Set<Int>? = if (!Cal.granted(a)) null else {
            val monthStart = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val monthEnd = Calendar.getInstance().apply { timeInMillis = monthStart; add(Calendar.MONTH, 1) }.timeInMillis
            val firstDay = Cal.julian(monthStart)
            val set = HashSet<Int>()
            for (e in Cal.instances(a, o, monthStart, monthEnd)) for (d in e.startDay..e.endDay) set += d - firstDay + 1
            set
        }
        val sb = android.text.SpannableStringBuilder()
        sb.append(HomeStyle.case(title)).append("\n")
        sb.append(names.joinToString(" ") { it.padStart(2).lowercase() }).append("\n")
        var col = 0
        repeat(first) { sb.append("   "); col++ }
        for (d in 1..days) {
            val s = d.toString().padStart(2)
            val start = sb.length
            sb.append(s)
            if (d == today) {
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, 0)
                sb.setSpan(android.text.style.UnderlineSpan(), start, sb.length, 0)
            } else if (marked != null && d !in marked) {
                sb.setSpan(android.text.style.ForegroundColorSpan(faintColor()), start, sb.length, 0)
            }
            col++
            if (col % 7 == 0) sb.append("\n") else sb.append(" ")
        }
        box.addView(text(a, sb, 0.95f).apply { setOnClickListener { Cal.openAt(a, o) } })
    }

    private fun faintColor(): Int = (Prefs.textColor and 0x00FFFFFF) or (0x55 shl 24)

    private fun agenda(a: Activity, box: LinearLayout, o: JSONObject) {
        if (!Cal.granted(a)) {
            box.addView(text(a, HomeStyle.case("calendar: tap to allow access"), 1f, dim = true).apply { setOnClickListener { Cal.ask(a) } })
            return
        }
        val days = o.optInt("days", 7).coerceIn(1, 60)
        // 0 = no limit; a scrolling widget shows everything by default
        val max = o.optInt("max", if (o.optInt("h", 0) > 0) 0 else 8).let { if (it <= 0) Int.MAX_VALUE else it }
        val showAllDay = o.optBoolean("allday", true)
        val now = System.currentTimeMillis()
        val dayStart = Usage.startOfDay()
        val dayEnd = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_YEAR, days) }.timeInMillis
        val today = Cal.julian(now)
        val lastDay = Cal.julian(dayEnd - 1)
        // an event that started earlier but is still running is listed under today
        val items = Cal.instances(a, o, dayStart, dayEnd)
            .filter { (showAllDay || !it.allDay) && (it.allDay || it.end > now) }
            .map { maxOf(it.startDay, today) to it }
            .filter { it.first <= lastDay }
            .sortedWith(compareBy({ it.first }, { !it.second.allDay }, { it.second.begin }))
        val left = curAlign == 0
        val is24 = when (Prefs.clockMode) { 1 -> true; 2 -> false; else -> android.text.format.DateFormat.is24HourFormat(a) }
        val tf = SimpleDateFormat(if (is24) "HH:mm" else "h:mma", Locale.getDefault())
        val df = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        fun dayMs(j: Int) = Calendar.getInstance().apply { timeInMillis = dayStart; add(Calendar.DAY_OF_YEAR, j - today) }.timeInMillis
        fun dayName(j: Int) = when (j - today) { 0 -> "today"; 1 -> "tomorrow"; else -> df.format(dayMs(j)) }

        if (items.isEmpty()) {
            box.addView(text(a, HomeStyle.case(if (days == 1) "nothing today" else "nothing in the next $days days"), 1f, dim = true)
                .apply { setOnClickListener { Cal.openAt(a, o) } })
            return
        }
        var lastShown = -1
        for ((idx, pair) in items.withIndex()) {
            val (day, e) = pair
            if (idx >= max) {
                box.addView(text(a, HomeStyle.case("+ ${items.size - max} more"), 0.85f, dim = true).apply {
                    setPadding(0, Ui.dp(a, 4), 0, 0); setOnClickListener { Cal.openAt(a, o) }
                })
                break
            }
            if (day != lastShown) {
                lastShown = day
                box.addView(text(a, HomeStyle.case(dayName(day)), 0.85f, dim = true).apply {
                    setPadding(0, if (idx == 0) 0 else Ui.dp(a, 8), 0, Ui.dp(a, 2))
                    setOnClickListener { Cal.openAt(a, o, dayMs(day)) }
                })
            }
            val time = when {
                e.allDay -> "all day"
                e.startDay < day -> "→" + tf.format(e.end).lowercase()
                else -> tf.format(e.begin).lowercase()
            }
            val until = if (e.endDay > day) "  → " + (if (e.endDay - today == 1) "tomorrow" else df.format(dayMs(e.endDay))) else ""
            val line = if (left) "  " + time.padEnd(7) + " " + e.title + until else time + "  " + e.title + until
            box.addView(text(a, HomeStyle.case(line)).apply {
                setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 2))
                // wrapped title lines hang under the title, not under the time
                if (left) {
                    val sp = android.text.SpannableString(text)
                    sp.setSpan(android.text.style.LeadingMarginSpan.Standard(0, paint.measureText("  " + "".padEnd(7) + " ").toInt()), 0, sp.length, 0)
                    text = sp
                }
                if (!left) gravity = HomeStyle.gravity(curAlign)
                setOnClickListener { Cal.openEvent(a, o, e) }
            })
        }
    }

    private val PROMPTS = listOf(
        "what did you pick up the phone for?",
        "one thing at a time.",
        "is this the best use of the next ten minutes?",
        "look up. what's around you?",
        "boredom is where ideas come from.",
        "you don't have to check.",
        "breathe in for four, out for six.",
        "who could you call instead of scroll?",
        "the feed will still be there. will this moment?",
        "do the hard thing first.",
        "drink some water.",
        "go outside for five minutes.",
        "notice three things you can hear.",
        "what would make today a good day?",
        "rest is productive too.",
        "close the app before it closes your day.",
        "attention is the rarest form of generosity.",
        "the phone is a tool, not a destination.",
    )
}

object HomeStyle {
    fun gravity(align: Int) = when (align) {
        1 -> Gravity.CENTER_HORIZONTAL
        2 -> Gravity.END
        else -> Gravity.START
    }

    fun case(s: String): String = when (Prefs.textCase) {
        1 -> s.lowercase()
        2 -> s.uppercase()
        else -> s
    }

    fun sp(tv: TextView, size: Int) = tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())

    fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val g = when (h) { in 5..11 -> "good morning"; in 12..16 -> "good afternoon"; in 17..21 -> "good evening"; else -> "good night" }
        return if (Prefs.greetName.isBlank()) "$g." else "$g, ${Prefs.greetName.trim()}."
    }
}
