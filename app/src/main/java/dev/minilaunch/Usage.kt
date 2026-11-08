package dev.minilaunch

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import java.util.Calendar

// No polling: launching a limited app arms one alarm for when its budget runs out.
// When it fires we check whether the app is still in front, notify, and re-arm.
object Usage {
    @Suppress("DEPRECATION") // the replacement needs API 29+ AND a non-null attribution tag
    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private var cache: Map<String, Long> = emptyMap()
    private var cacheAt = 0L
    @Volatile var unlocks = 0; private set

    // ms in foreground per package since midnight. Not for the main thread.
    fun today(ctx: Context, maxAgeMs: Long = 30_000): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (now - cacheAt < maxAgeMs) return cache
        if (!hasAccess(ctx)) return emptyMap()
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(startOfDay(), now)
        val e = UsageEvents.Event()
        val open = HashMap<String, HashSet<String>>()
        val since = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        var unl = 0
        fun close(p: String, t: Long) {
            val s = since.remove(p) ?: return
            open.remove(p)
            total[p] = (total[p] ?: 0) + (t - s)
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val p = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val set = open.getOrPut(p) { HashSet() }
                    if (set.isEmpty()) since[p] = e.timeStamp
                    set += e.className ?: ""
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val set = open[p] ?: continue
                    set -= e.className ?: ""
                    if (set.isEmpty()) close(p, e.timeStamp)
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> unl++
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN ->
                    since.keys.toList().forEach { close(it, e.timeStamp) }
            }
        }
        since.keys.toList().forEach { close(it, now) }
        total.remove(ctx.packageName)
        cache = total; cacheAt = now; unlocks = unl
        return total
    }

    fun foreground(ctx: Context): String? {
        if (!hasAccess(ctx)) return null
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 30 * 60_000, now)
        val e = UsageEvents.Event()
        var fg: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> fg = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (fg == e.packageName) fg = null
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> fg = null
            }
        }
        return fg
    }

    fun fmt(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    fun limitMinutes(pkg: String) = Prefs.limits.optJSONObject(pkg)?.optInt("m", 0) ?: 0
    fun delaySeconds(pkg: String) = Prefs.limits.optJSONObject(pkg)?.optInt("d", 0) ?: 0

    fun setLimit(pkg: String, minutes: Int?, delay: Int?) {
        val all = Prefs.limits
        val o = all.optJSONObject(pkg) ?: org.json.JSONObject()
        if (minutes != null) o.put("m", minutes)
        if (delay != null) o.put("d", delay)
        if (o.optInt("m") <= 0 && o.optInt("d") <= 0) all.remove(pkg) else all.put(pkg, o)
        Prefs.limits = all
    }

    private fun pi(ctx: Context, pkg: String) = PendingIntent.getBroadcast(
        ctx, pkg.hashCode(),
        Intent(ctx, LimitReceiver::class.java).setPackage(ctx.packageName).putExtra("pkg", pkg),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context, pkg: String, inMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.set(AlarmManager.RTC, System.currentTimeMillis() + inMs.coerceAtLeast(30_000), pi(ctx, pkg))
    }

    fun armFor(ctx: Context, pkg: String, usedMs: Long) {
        val lim = limitMinutes(pkg)
        if (lim <= 0) return
        val left = lim * 60_000L - usedMs
        schedule(ctx, pkg, if (left > 0) left else Prefs.remindEvery * 60_000L)
    }

    const val CHANNEL = "limits"

    fun notify(ctx: Context, pkg: String, label: String, usedMs: Long, limitMin: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App limit reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tells you when an app passed its daily limit"
                setShowBadge(false)
            })
        }
        val home = PendingIntent.getActivity(ctx, 1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("$label: time's up")
            .setContentText("${fmt(usedMs)} today · limit ${limitMin}m. Maybe put the phone down?")
            .setContentIntent(home)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        runCatching { nm.notify(pkg.hashCode(), n) }
    }
}

class LimitReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: return
        Prefs.init(ctx)
        val pending = goAsync()
        Thread {
            try {
                val lim = Usage.limitMinutes(pkg)
                if (lim > 0 && Usage.foreground(ctx) == pkg) {
                    val used = Usage.today(ctx, 0)[pkg] ?: 0
                    val left = lim * 60_000L - used
                    if (left <= 0) {
                        val label = runCatching {
                            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
                        }.getOrDefault(pkg)
                        Usage.notify(ctx, pkg, label, used, lim)
                        Usage.schedule(ctx, pkg, Prefs.remindEvery * 60_000L)
                    } else {
                        Usage.schedule(ctx, pkg, left)
                    }
                }
            } finally { pending.finish() }
        }.start()
    }
}
