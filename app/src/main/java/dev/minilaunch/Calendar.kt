package dev.minilaunch

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

// Reads the shared Android calendar store. Samsung Calendar, Google Calendar, Outlook etc.
// all write into it, so one query covers every account on the phone.
object Cal {
    const val REQ = 12

    fun granted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun ask(a: Activity) = a.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ)

    class CalInfo(val id: Long, val name: String, val group: String, val visible: Boolean)

    fun calendars(ctx: Context): List<CalInfo> = runCatching {
        val out = ArrayList<CalInfo>()
        ctx.contentResolver.query(Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.VISIBLE),
            "${Calendars.SYNC_EVENTS}=1", null, null)?.use { c ->
            while (c.moveToNext()) out += CalInfo(c.getLong(0), c.getString(1) ?: "?",
                group(c.getString(3) ?: "", c.getString(2) ?: ""), c.getInt(4) == 1)
        }
        out.sortedWith(compareBy({ it.group.lowercase() }, { it.name.lowercase() }))
    }.getOrDefault(emptyList())

    private fun group(type: String, account: String): String = when {
        type == "com.google" -> "google · $account"
        type == "com.osp.app.signin" -> "samsung account"
        type == CalendarContract.ACCOUNT_TYPE_LOCAL -> "on this phone"
        "microsoft" in type || "outlook" in type -> "outlook · $account"
        else -> "${type.substringAfterLast('.')} · $account"
    }

    // null = follow whatever the calendar apps show (the shared 'visible' flag)
    fun chosen(o: JSONObject): List<Long>? = o.optJSONArray("cals")?.let { a -> List(a.length()) { a.getLong(it) } }

    fun describe(o: JSONObject): String = chosen(o)?.let { "${it.size} calendars" } ?: "calendars shown in your calendar app"

    class Item(val eventId: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean,
               val startDay: Int, val endDay: Int)

    fun julian(ms: Long): Int = ((ms + TimeZone.getDefault().getOffset(ms)) / 86_400_000L).toInt() + 2440588

    // instances overlapping julian days [fromDay, toDay]; all-day events are stored in UTC, hence the wider window
    fun instances(ctx: Context, o: JSONObject, fromMs: Long, toMs: Long): List<Item> {
        if (!granted(ctx)) return emptyList()
        val ids = chosen(o)
        if (ids != null && ids.isEmpty()) return emptyList()
        val sel = StringBuilder(if (ids == null) "${Instances.VISIBLE}=1" else "${Instances.CALENDAR_ID} IN (${ids.joinToString(",")})")
        sel.append(" AND (${Instances.SELF_ATTENDEE_STATUS} IS NULL OR ${Instances.SELF_ATTENDEE_STATUS}!=${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})")
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs - 86_400_000L); ContentUris.appendId(it, toMs + 86_400_000L)
        }.build()
        val fromDay = julian(fromMs); val toDay = julian(toMs - 1)
        val out = ArrayList<Item>()
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END,
                Instances.ALL_DAY, Instances.START_DAY, Instances.END_DAY), sel.toString(), null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val it = Item(c.getLong(0), c.getString(1)?.trim().orEmpty().ifEmpty { "(no title)" }, c.getLong(2), c.getLong(3),
                        c.getInt(4) == 1, c.getInt(5), c.getInt(6))
                    if (it.endDay >= fromDay && it.startDay <= toDay) out += it
                }
            }
        }
        return out
    }

    // apps that can show a calendar day, as pkg -> distinct label
    fun apps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val pkgs = pm.queryIntentActivities(timeIntent(System.currentTimeMillis()), 0).map { it.activityInfo.packageName }.distinct()
        return pkgs.map { it to Names.app(ctx, it) }.sortedBy { it.second.lowercase() }
    }

    fun appName(ctx: Context, o: JSONObject): String = o.optString("app").ifEmpty { null }?.let { Names.app(ctx, it) } ?: "system default"

    private fun timeIntent(ms: Long) = Intent(Intent.ACTION_VIEW,
        CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(ms.toString()).build())

    fun openAt(a: Activity, o: JSONObject, ms: Long = System.currentTimeMillis()) = open(a, o, timeIntent(ms))

    fun openEvent(a: Activity, o: JSONObject, e: Item) = open(a, o,
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end))

    private fun open(a: Activity, o: JSONObject, i: Intent) {
        val pkg = o.optString("app")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg.isNotEmpty()) {
            if (runCatching { a.startActivity(Intent(i).setPackage(pkg)) }.isSuccess) return
            a.packageManager.getLaunchIntentForPackage(pkg)?.let { if (runCatching { a.startActivity(it) }.isSuccess) return }
        }
        Actions.start(a, i)
    }

    fun pickApp(a: Activity, o: JSONObject, onPick: (String) -> Unit) {
        val apps = apps(a)
        val opts = listOf("system default") + apps.map { it.second }
        val cur = apps.indexOfFirst { it.first == o.optString("app") } + 1
        Ui.choice(a, "open events in", opts, cur) { i -> onPick(if (i == 0) "" else apps[i - 1].first) }
    }

    // multi-select grouped by account; onDone(null) = follow the calendar apps
    fun pickCalendars(a: Activity, o: JSONObject, onDone: (JSONArray?) -> Unit) {
        if (!granted(a)) { ask(a); return }
        val cals = calendars(a)
        var follow = chosen(o) == null
        val sel = (chosen(o) ?: cals.filter { it.visible }.map { it.id }).toMutableSet()
        val s = Ui.Sheet(a, "calendars")
        val rows = ArrayList<Pair<TextView, () -> String>>()
        fun refresh() = rows.forEach { (t, f) -> t.text = f() }
        fun addRow(text: () -> String, sub: String?, click: () -> Unit) {
            val r = Ui.row(a, text(), sub) { click(); refresh() } as LinearLayout
            rows += (r.getChildAt(0) as TextView) to text
            s.body.addView(r)
        }
        addRow({ (if (follow) "(•) " else "( ) ") + "same as my calendar app" }, "whatever is ticked in samsung / google calendar") {
            follow = true; sel.clear(); sel += cals.filter { it.visible }.map { it.id }
        }
        addRow({ (if (!follow) "(•) " else "( ) ") + "choose below" }, null) { follow = false }
        var last = ""
        for (c in cals) {
            if (c.group != last) {
                last = c.group
                s.body.addView(Ui.tv(a, c.group.uppercase(), 12f, Ui.DIM).apply {
                    setPadding(0, Ui.dp(a, 16), 0, Ui.dp(a, 2)); letterSpacing = 0.06f
                })
            }
            addRow({ (if (c.id in sel) "[x] " else "[ ] ") + c.name }, null) {
                follow = false
                if (!sel.add(c.id)) sel.remove(c.id)
            }
        }
        if (cals.isEmpty()) s.body.addView(Ui.tv(a, "no calendars found on this phone.", 14f, Ui.DIM))
        s.scrollBody().button("cancel").button("save") { onDone(if (follow) null else JSONArray(sel.toList())) }
        s.show()
    }
}

// Human-readable app names that tell apart apps sharing a label (Samsung vs Google "Calendar").
object Names {
    private val tlds = setOf("com", "org", "net", "io", "dev", "de", "app", "me", "co", "cz", "uk", "ch")

    fun maker(pkg: String): String {
        val p = pkg.split('.')
        val m = if (p.size >= 2 && p[0] in tlds) p[1] else p[0]
        return when (m) {
            "sec", "samsung" -> "Samsung"
            "google" -> "Google"
            "android" -> "Android"
            "microsoft" -> "Microsoft"
            else -> m.replaceFirstChar { it.uppercase() }
        }
    }

    fun label(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // adds "(maker)" when another installed app has the same name
    fun app(ctx: Context, pkg: String, others: Map<String, String> = emptyMap()): String {
        val l = label(ctx, pkg)
        val clash = Apps.all.any { it.pkg != pkg && it.sysLabel.equals(l, true) } ||
            others.any { it.key != pkg && it.value.equals(l, true) }
        return if (clash) "$l (${maker(pkg)})" else l
    }
}
