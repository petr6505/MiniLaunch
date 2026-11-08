package dev.minilaunch

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

// Only used for global actions (lock, recents). Scoped to our own package in accessibility.xml.
class LockService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private var instance: LockService? = null
        val enabled get() = instance != null
        fun lock() = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
        fun recents() = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
        fun notifications() = instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ?: false
        fun quickSettings() = instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) ?: false
    }
}
