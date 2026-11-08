package dev.minilaunch

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings

// Recents is still drawn by One UI Home. Its "show recommended apps" switch is stored in
// One UI Home's private prefs (no Settings key changes when toggled), so just open that screen.
object RecentsTweaks {
    private val RECENTS_SETTINGS = ComponentName(
        "com.sec.android.app.launcher", "com.android.quickstep.RecentsSettingsActivity")

    fun rows(a: Activity): List<Triple<String, String?, () -> Unit>> = listOf(
        Triple("hide recommended apps in recents", "opens samsung's recents settings → switch off 'show recommended apps'") {
            openSystemRecentsSettings(a)
        },
    )

    fun openSystemRecentsSettings(a: Activity) {
        val tries = listOf(
            Intent().setComponent(RECENTS_SETTINGS),
            Intent(Settings.ACTION_HOME_SETTINGS),
        )
        for (i in tries) {
            try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Throwable) {}
        }
        Ui.info(a, "recents", "Open recents → ⋮ → Settings → turn off 'Show recommended apps'.")
    }
}
