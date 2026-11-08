package dev.minilaunch

import android.app.Activity
import android.app.Application
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Toast

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}

/** Opening apps, with the optional mindful-delay and daily-limit interventions in between. */
object Launcher {
    private val main = Handler(Looper.getMainLooper())

    fun launch(a: Activity, app: AppEntry, bounds: Rect? = null) {
        val limit = Usage.limitMinutes(app.pkg)
        val delay = Usage.delaySeconds(app.pkg)
        if (limit <= 0 && delay <= 0) { open(a, app, bounds); return }

        Thread {
            val used = if (Usage.hasAccess(a)) Usage.today(a, 0)[app.pkg] ?: 0L else 0L
            main.post {
                if (a.isFinishing) return@post
                val over = limit > 0 && used >= limit * 60_000L
                when {
                    over -> overLimit(a, app, used, limit, bounds)
                    delay > 0 -> pause(a, app, used, limit, delay, bounds)
                    else -> { open(a, app, bounds); Usage.armFor(a, app.pkg, used) }
                }
            }
        }.start()
    }

    private fun open(a: Activity, app: AppEntry, bounds: Rect?) {
        try {
            a.getSystemService(LauncherApps::class.java).startMainActivity(app.component, app.user, bounds, null)
        } catch (t: Throwable) {
            Toast.makeText(a, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun usedLine(used: Long, limit: Int) =
        if (limit > 0) "used ${Usage.fmt(used)} of ${limit}m today" else "used ${Usage.fmt(used)} today"

    /** "Take a breath" countdown before the app opens. */
    private fun pause(a: Activity, app: AppEntry, used: Long, limit: Int, seconds: Int, bounds: Rect?) {
        val s = Ui.Sheet(a, null)
        val big = Ui.tv(a, "$seconds", 48f, Ui.FG, bold = true).apply { gravity = Gravity.CENTER }
        val breathe = Ui.tv(a, "breathe.", 20f, Ui.FG).apply { gravity = Gravity.CENTER }
        s.body.addView(breathe)
        s.body.addView(big)
        s.body.addView(Ui.tv(a, "opening ${app.label}\n${usedLine(used, limit)}", 14f, Ui.DIM).apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8))
        })
        var left = seconds
        var cancelled = false
        s.button("not now") { cancelled = true }
        val d = s.show()
        // A slow in-and-out, like a breath (4 s in, 4 s out) – only while this dialog is open.
        val anim = android.animation.ObjectAnimator.ofPropertyValuesHolder(breathe,
            android.animation.PropertyValuesHolder.ofFloat(android.view.View.SCALE_X, 0.92f, 1.12f),
            android.animation.PropertyValuesHolder.ofFloat(android.view.View.SCALE_Y, 0.92f, 1.12f),
            android.animation.PropertyValuesHolder.ofFloat(android.view.View.ALPHA, 0.55f, 1f)).apply {
            duration = 4000; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        if (Prefs.motion) anim.start()
        d.setOnDismissListener { cancelled = true; anim.cancel() }
        val tick = object : Runnable {
            override fun run() {
                if (cancelled) return
                left--
                if (left <= 0) {
                    cancelled = true; d.dismiss()
                    open(a, app, bounds); Usage.armFor(a, app.pkg, used)
                } else { big.text = "$left"; main.postDelayed(this, 1000) }
            }
        }
        main.postDelayed(tick, 1000)
    }

    private fun overLimit(a: Activity, app: AppEntry, used: Long, limit: Int, bounds: Rect?) {
        val s = Ui.Sheet(a, "limit reached")
        s.body.addView(Ui.tv(a, "${app.label}\n${usedLine(used, limit)}.\n\nIs this what you meant to do?", 15f))
        s.button("close")
        if (!Prefs.limitBlocks) {
            s.button("open anyway") { open(a, app, bounds); Usage.schedule(a, app.pkg, Prefs.remindEvery * 60_000L) }
            s.show()
        } else {
            // Strict mode: you can still open it, but only after waiting 15 seconds.
            var left = 15
            lateinit var btn: android.widget.TextView
            val d = s.show()
            btn = Ui.button(a, "wait $left s") {}
            s.body.addView(btn)
            val tick = object : Runnable {
                override fun run() {
                    if (!d.isShowing) return
                    left--
                    if (left <= 0) {
                        btn.text = "[ open anyway ]"
                        btn.setOnClickListener { d.dismiss(); open(a, app, bounds); Usage.schedule(a, app.pkg, Prefs.remindEvery * 60_000L) }
                    } else { btn.text = "[ wait $left s ]"; main.postDelayed(this, 1000) }
                }
            }
            main.postDelayed(tick, 1000)
        }
    }
}
