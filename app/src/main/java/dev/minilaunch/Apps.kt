package dev.minilaunch

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.text.Normalizer
import java.util.Locale

class AppEntry(
    val key: String,
    val pkg: String,
    val component: ComponentName,
    val user: UserHandle,
    val sysLabel: String,
    val work: Boolean,
) {
    val label: String get() = Prefs.label(key) ?: sysLabel
    /** Lower-cased, accent-stripped label used for searching. */
    val norm: String get() = Apps.normalize(label)
}

/**
 * In-memory app list. Loaded once, then kept up to date by a LauncherApps callback
 * (no polling, no broadcast receivers that wake the app).
 */
object Apps {
    @Volatile var all: List<AppEntry> = emptyList(); private set
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private val diacritics = Regex("\\p{Mn}+")

    fun normalize(s: String): String =
        diacritics.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun byKey(key: String): AppEntry? = all.firstOrNull { it.key == key }

    fun visible(): List<AppEntry> {
        val hidden = Prefs.hidden
        return all.filter { it.key !in hidden }
    }

    fun load(ctx: Context) {
        val la = ctx.getSystemService(LauncherApps::class.java)
        val um = ctx.getSystemService(UserManager::class.java)
        if (!registered) {
            registered = true
            la.registerCallback(object : LauncherApps.Callback() {
                override fun onPackageRemoved(p: String?, u: UserHandle?) = reload(ctx)
                override fun onPackageAdded(p: String?, u: UserHandle?) = reload(ctx)
                override fun onPackageChanged(p: String?, u: UserHandle?) = reload(ctx)
                override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = reload(ctx)
                override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = reload(ctx)
            }, main)
        }
        val me = Process.myUserHandle()
        val out = ArrayList<AppEntry>(256)
        for (user in um.userProfiles) {
            val list: List<LauncherActivityInfo> = runCatching { la.getActivityList(null, user) }.getOrDefault(emptyList())
            val serial = um.getSerialNumberForUser(user)
            for (info in list) {
                val cn = info.componentName
                if (cn.packageName == ctx.packageName) continue
                out += AppEntry(
                    key = "${cn.flattenToShortString()}#$serial",
                    pkg = cn.packageName,
                    component = cn,
                    user = user,
                    sysLabel = info.label.toString().trim(),
                    work = user != me,
                )
            }
        }
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        all = out
    }

    private fun reload(ctx: Context) {
        Thread {
            load(ctx)
            main.post { listeners.toList().forEach { it() } }
        }.start()
    }

    /** Re-sort after renames. */
    fun resort() {
        all = all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun search(query: String, includeHidden: Boolean, fuzzy: Boolean): List<AppEntry> {
        val src = if (includeHidden) all else visible()
        val q = normalize(query.trim())
        if (q.isEmpty()) return src
        // Score: 0 = prefix, 1 = word prefix, 2 = contains, 3 = initials / subsequence
        val scored = ArrayList<Pair<Int, AppEntry>>()
        for (a in src) {
            val n = a.norm
            val s = when {
                n.startsWith(q) -> 0
                n.split(' ', '-', '_', '.').any { it.startsWith(q) } -> 1
                n.contains(q) -> 2
                fuzzy && initials(n).startsWith(q) -> 3
                fuzzy && q.length >= 3 && subsequence(q, n) -> 4
                else -> -1
            }
            if (s >= 0) scored += s to a
        }
        scored.sortWith(compareBy<Pair<Int, AppEntry>> { it.first }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.second.label })
        return scored.map { it.second }
    }

    private fun initials(s: String) = s.split(' ', '-', '_').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }

    private fun subsequence(q: String, s: String): Boolean {
        var i = 0
        for (c in s) if (i < q.length && c == q[i]) i++
        return i == q.length
    }
}
