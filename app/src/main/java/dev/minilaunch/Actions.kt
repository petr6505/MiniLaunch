package dev.minilaunch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast

object Gestures {
    val all = listOf(
        "swipe_up" to "swipe up",
        "swipe_down" to "swipe down",
        "swipe_left" to "swipe left",
        "swipe_right" to "swipe right",
        "double_tap" to "double tap",
        "long_press" to "long press",
        "tap_clock" to "tap clock",
        "tap_date" to "tap date",
        "tap_battery" to "tap battery",
        "tap_weather" to "tap weather",
    )

    fun default(g: String) = when (g) {
        "swipe_up" -> "drawer"
        "swipe_down" -> "notifications"
        "swipe_left" -> "widgets"
        "swipe_right" -> "camera"
        "double_tap" -> "lock"
        "long_press" -> "menu"
        "tap_clock" -> "alarms"
        "tap_date" -> "calendar"
        "tap_battery" -> "battery"
        "tap_weather" -> "weather_refresh"
        else -> "none"
    }
}

object Actions {
    val builtIn = listOf(
        "none" to "do nothing",
        "drawer" to "app drawer",
        "search" to "app search (keyboard)",
        "widgets" to "widgets page",
        "notifications" to "notification shade",
        "quicksettings" to "quick settings",
        "lock" to "lock screen",
        "recents" to "recent apps",
        "menu" to "launcher menu",
        "settings" to "launcher settings",
        "grayscale" to "toggle grayscale",
        "torch" to "toggle flashlight",
        "camera" to "camera",
        "dialer" to "phone dialer",
        "alarms" to "clock / alarms",
        "calendar" to "calendar",
        "battery" to "battery usage",
        "weather_refresh" to "refresh weather",
        "screen_time" to "screen time (digital wellbeing)",
    )

    fun describe(action: String): String {
        if (action.startsWith("app:")) {
            val app = Apps.byKey(action.removePrefix("app:"))
            return "open: " + (app?.label ?: "(missing app)")
        }
        return builtIn.firstOrNull { it.first == action }?.second ?: action
    }

    // returns false for actions MainActivity has to handle itself
    fun runGlobal(a: Activity, action: String): Boolean {
        when (action) {
            "none" -> {}
            "notifications" -> expandStatusBar(a, false)
            "quicksettings" -> expandStatusBar(a, true)
            "lock" -> if (!LockService.lock()) askA11y(a)
            "recents" -> if (!LockService.recents()) askA11y(a)
            "settings" -> a.startActivity(Intent(a, SettingsActivity::class.java))
            "grayscale" -> toggleGrayscale(a)
            "torch" -> toggleTorch(a)
            "camera" -> start(a, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            "dialer" -> start(a, Intent(Intent.ACTION_DIAL))
            "alarms" -> start(a, Intent(AlarmClock.ACTION_SHOW_ALARMS))
            "calendar" -> start(a, Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon()
                .appendPath("time").appendPath(System.currentTimeMillis().toString()).build()))
            "battery" -> start(a, Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            "screen_time" -> openScreenTime(a)
            else -> return false
        }
        return true
    }

    fun start(ctx: Context, i: Intent) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "No app found for this action", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            // e.g. SHOW_ALARMS needs a permission on some ROMs - fall back to opening the app
            if (!openHandlerApp(ctx, i)) Toast.makeText(ctx, "Not allowed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openHandlerApp(ctx: Context, i: Intent): Boolean {
        val pkg = runCatching { ctx.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName }.getOrNull() ?: return false
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    @SuppressLint("WrongConstant")
    fun expandStatusBar(ctx: Context, quick: Boolean) {
        try {
            val sb = ctx.getSystemService("statusbar")
            val cls = Class.forName("android.app.StatusBarManager")
            cls.getMethod(if (quick) "expandSettingsPanel" else "expandNotificationsPanel").invoke(sb)
        } catch (_: Throwable) {
            val ok = if (quick) LockService.quickSettings() else LockService.notifications()
            if (!ok) Toast.makeText(ctx, "Can't open the shade on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askA11y(a: Activity) {
        Ui.confirm(a, "Enable accessibility",
            "Locking the screen and opening recents need MiniLaunch's accessibility service.\n\n" +
                "It reads nothing on screen - it only performs that one system action.\n\n" +
                "Settings → Installed apps → MiniLaunch → on.\n\n" +
                "(If it's greyed out: App info → ⋮ → Allow restricted settings first.)", "open settings") {
            start(a, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    fun hasSecureSettings(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isGrayscale(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return Settings.Secure.getInt(cr, "accessibility_display_daltonizer_enabled", 0) == 1 &&
            Settings.Secure.getInt(cr, "accessibility_display_daltonizer", -1) == 0
    }

    fun setGrayscale(ctx: Context, on: Boolean): Boolean {
        if (!hasSecureSettings(ctx)) return false
        val cr = ctx.contentResolver
        Settings.Secure.putInt(cr, "accessibility_display_daltonizer_enabled", if (on) 1 else 0)
        if (on) Settings.Secure.putInt(cr, "accessibility_display_daltonizer", 0)
        return true
    }

    private fun toggleGrayscale(a: Activity) {
        if (!setGrayscale(a, !isGrayscale(a))) {
            Ui.info(a, "Grayscale needs a one-time permission",
                "Run this once from a computer:\n\nadb shell pm grant ${a.packageName} android.permission.WRITE_SECURE_SETTINGS")
        }
    }

    private var torchOn = false
    private fun toggleTorch(ctx: Context) {
        try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        } catch (_: Throwable) {
            Toast.makeText(ctx, "Flashlight unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openScreenTime(ctx: Context) {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.samsung.android.forest", "com.samsung.android.forest.launcher.LauncherActivity")),
            Intent().setComponent(ComponentName("com.google.android.apps.wellbeing", "com.google.android.apps.wellbeing.settings.TopLevelSettingsActivity")),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in candidates) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Throwable) {}
        }
    }

    fun appInfo(ctx: Context, app: AppEntry) {
        val la = ctx.getSystemService(android.content.pm.LauncherApps::class.java)
        try { la.startAppDetailsActivity(app.component, app.user, null, null) }
        catch (_: Throwable) { start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))) }
    }

    fun uninstall(ctx: Context, app: AppEntry) {
        start(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))
            .putExtra(Intent.EXTRA_USER, app.user))
    }
}
