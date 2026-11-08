package dev.minilaunch

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.os.UserManager

object WidgetPicker {
    const val REQ_BIND = 7101
    const val REQ_CONF = 7102
    private var pendingId = -1
    private var pendingInfo: AppWidgetProviderInfo? = null
    var onAdded: (() -> Unit)? = null

    private var pendingPos = 0

    fun open(a: Activity) {
        class Row(val header: String? = null, val own: String? = null, val info: AppWidgetProviderInfo? = null,
                  val title: String = "", val sub: String = "")
        val pm = a.packageManager
        val d = a.resources.displayMetrics.density
        val awm = WidgetHost.manager(a)
        val all = ArrayList<Row>()
        all += Row(header = "minilaunch")
        for ((k, label) in OwnWidgets.types) all += Row(own = k, title = label, sub = "text only · updates when visible")
        all += Row(header = "android")
        val appLabel = HashMap<String, String>()
        a.getSystemService(UserManager::class.java).userProfiles
            .flatMap { u -> runCatching { awm.getInstalledProvidersForProfile(u) }.getOrDefault(emptyList()) }
            .map { p ->
                val app = appLabel.getOrPut(p.provider.packageName) {
                    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString() }
                        .getOrDefault(p.provider.packageName)
                }
                Row(info = p, title = "$app - ${p.loadLabel(pm)}",
                    sub = "${p.targetCellWidth.takeIf { it > 0 } ?: ((p.minWidth / d + 30) / 70).toInt().coerceAtLeast(1)}×" +
                        "${p.targetCellHeight.takeIf { it > 0 } ?: ((p.minHeight / d + 30) / 70).toInt().coerceAtLeast(1)}")
            }.sortedBy { it.title.lowercase() }.forEach { all += it }

        val s = Ui.Sheet(a, "add widget")
        val search = Ui.edit(a, "", "search widgets…")
        var rows: List<Row> = all
        val previews = HashMap<String, android.graphics.drawable.Drawable?>()
        val iconOnly = HashSet<String>()
        val loader = java.util.concurrent.Executors.newSingleThreadExecutor()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun keyOf(i: AppWidgetProviderInfo) = i.provider.flattenToShortString() + i.profile.hashCode()

        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getViewTypeCount() = 3
            override fun getItemViewType(p: Int) = when { rows[p].header != null -> 0; rows[p].own != null -> 1; else -> 2 }
            override fun isEnabled(p: Int) = rows[p].header == null
            override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
                val r = rows[p]
                if (r.header != null) {
                    val t = (v as? TextView) ?: Ui.tv(a, "", 12f, Ui.DIM).apply {
                        setPadding(0, Ui.dp(a, 16), 0, Ui.dp(a, 4)); letterSpacing = 0.1f
                    }
                    t.text = r.header.uppercase(); return t
                }
                val box = (v as? LinearLayout) ?: LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = Ui.dp(a, 10); setPadding(0, pad, 0, pad)
                    addView(Ui.tv(a, "", 15f)); addView(Ui.tv(a, "", 12f, Ui.DIM).apply { setPadding(0, Ui.dp(a, 3), 0, Ui.dp(a, 6)) })
                    if (r.own != null) addView(Ui.tv(a, "", 12f, 0xFFBBBBBB.toInt()).apply {
                        val q = Ui.dp(a, 10); setPadding(q, q, q, q); background = Ui.box(a, fill = 0xFF1A1A1A.toInt())
                        setLineSpacing(0f, 1.15f)
                    }) else addView(android.widget.ImageView(a).apply {
                        adjustViewBounds = true; maxHeight = Ui.dp(a, 130)
                        scaleType = android.widget.ImageView.ScaleType.FIT_START
                    }, LinearLayout.LayoutParams(MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                (box.getChildAt(0) as TextView).text = r.title
                (box.getChildAt(1) as TextView).text = r.sub
                if (r.own != null) (box.getChildAt(2) as TextView).text = OwnWidgets.sample(r.own)
                else {
                    val img = box.getChildAt(2) as android.widget.ImageView
                    val info = r.info!!
                    val k = keyOf(info)
                    img.tag = k
                    fun put(dr: android.graphics.drawable.Drawable?) {
                        // icon fallbacks look silly at full preview size
                        img.maxHeight = Ui.dp(a, if (k in iconOnly) 44 else 130)
                        img.setImageDrawable(dr)
                    }
                    if (previews.containsKey(k)) put(previews[k])
                    else {
                        img.setImageDrawable(null)
                        loader.execute {
                            val pv = runCatching { info.loadPreviewImage(a, 0) }.getOrNull()
                            val dr = pv ?: runCatching { info.loadIcon(a, 0) }.getOrNull()
                            main.post { if (pv == null) iconOnly += k; previews[k] = dr; if (img.tag == k) put(dr) }
                        }
                    }
                }
                return box
            }
        }
        val list = ListView(a).apply { divider = null; this.adapter = adapter; isVerticalScrollBarEnabled = false }
        list.setOnItemClickListener { _, _, p, _ ->
            val r = rows[p]
            s.dialog.dismiss()
            askPosition(a) { pos ->
                pendingPos = pos
                if (r.own != null) addOwn(a, r.own) else bind(a, r.info!!)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                val q = Apps.normalize(e.toString())
                rows = if (q.isEmpty()) all else all.filter { it.header == null && Apps.normalize(it.title).contains(q) }
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(c: CharSequence?, s: Int, b: Int, n: Int) {}
            override fun onTextChanged(c: CharSequence?, s: Int, b: Int, n: Int) {}
        })
        s.body.addView(search)
        s.body.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, (a.resources.displayMetrics.heightPixels * 0.6f).toInt()))
        s.button("cancel")
        s.show().setOnDismissListener { loader.shutdownNow() }
    }

    private fun askPosition(a: Activity, then: (Int) -> Unit) {
        Ui.choice(a, "where should it go?", WidgetHost.positions, Prefs.widgetDefaultPos) { then(it) }
    }

    private fun addOwn(a: Activity, k: String) {
        val o = OwnWidgets.create(k).put("pos", pendingPos)
        if (k == "countdown") {
            Ui.input(a, "countdown: what?", "", "e.g. holiday") { label ->
                Ui.input(a, "date (yyyy-mm-dd)", "", "2027-01-01") { date ->
                    o.put("label", label).put("date", date.trim()); WidgetHost.add(o); onAdded?.invoke()
                }
            }
        } else { WidgetHost.add(o); onAdded?.invoke() }
    }

    private fun bind(a: Activity, info: AppWidgetProviderInfo) {
        val host = WidgetHost.host(a)
        val id = host.allocateAppWidgetId()
        pendingId = id; pendingInfo = info
        if (WidgetHost.manager(a).bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configure(a)
        } else {
            a.startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile), REQ_BIND)
        }
    }

    private fun configure(a: Activity) {
        val info = pendingInfo ?: return
        if (info.configure != null && (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) == 0) {
            try {
                WidgetHost.host(a).startAppWidgetConfigureActivityForResult(a, pendingId, 0, REQ_CONF, null)
                return
            } catch (_: Throwable) { /* fall through and add unconfigured */ }
        }
        finish(a)
    }

    private fun finish(a: Activity) {
        val info = pendingInfo ?: return
        val hDp = (info.minHeight / a.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        WidgetHost.add(org.json.JSONObject().put("t", "sys").put("id", pendingId).put("h", hDp).put("pos", pendingPos))
        pendingId = -1; pendingInfo = null
        onAdded?.invoke()
    }

    fun onResult(a: Activity, req: Int, res: Int, data: Intent?) {
        if (req != REQ_BIND && req != REQ_CONF) return
        if (pendingId == -1) return
        if (res != Activity.RESULT_OK) {
            WidgetHost.host(a).deleteAppWidgetId(pendingId); pendingId = -1; pendingInfo = null; return
        }
        if (req == REQ_BIND) configure(a) else finish(a)
    }
}

object LimitsUi {
    private val minuteOpts = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240)
    private val delayOpts = listOf(0, 3, 5, 10, 15, 30)

    fun ensureAccess(a: Activity, then: () -> Unit) {
        if (Usage.hasAccess(a)) { then(); return }
        Ui.confirm(a, "usage access needed",
            "To know how long you've used an app today, MiniLaunch needs 'Usage access'.\n\n" +
                "The data never leaves your phone and is only read when you open a limited app or look at the screen-time widget.",
            "grant") { Actions.start(a, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    private fun ensureNotify(a: Activity) {
        if (a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
    }

    fun editLimit(a: Activity, app: AppEntry, done: () -> Unit) = ensureAccess(a) {
        val cur = Usage.limitMinutes(app.pkg)
        Ui.choice(a, "daily limit: ${app.label}", minuteOpts.map { if (it == 0) "no limit" else fmt(it) },
            minuteOpts.indexOf(cur).coerceAtLeast(0)) { i ->
            Usage.setLimit(app.pkg, minuteOpts[i], null)
            if (minuteOpts[i] > 0) ensureNotify(a)
            done()
        }
    }

    fun editDelay(a: Activity, app: AppEntry, done: () -> Unit) {
        val cur = Usage.delaySeconds(app.pkg)
        Ui.choice(a, "mindful pause before opening ${app.label}", delayOpts.map { if (it == 0) "off" else "$it seconds" },
            delayOpts.indexOf(cur).coerceAtLeast(0)) { i ->
            Usage.setLimit(app.pkg, null, delayOpts[i]); done()
        }
    }

    private fun fmt(m: Int) = if (m >= 60) "${m / 60}h" + (if (m % 60 > 0) " ${m % 60}m" else "") + " / day" else "$m min / day"
}
