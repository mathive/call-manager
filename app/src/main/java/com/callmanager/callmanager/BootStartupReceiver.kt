package com.callmanager.callmanager

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.util.Log

class BootStartupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val whatsappBlockingEnabled = prefs.getBoolean("block_whatsapp_unknown", false)
        val blockUnknownEnabled = prefs.getBoolean("block_all_unknown", false)
        val edgeGlowEnabled = prefs.getBoolean("edge_glow_enabled", false)

        Log.d(
            "BootStartupReceiver",
            "Restoring background features after $action (unknown=$blockUnknownEnabled, whatsapp=$whatsappBlockingEnabled, glow=$edgeGlowEnabled)"
        )

        if (whatsappBlockingEnabled && isNotificationListenerEnabled(context)) {
            val component = ComponentName(context, NotificationBlockerService::class.java)
            try {
                NotificationListenerService.requestRebind(component)
                Log.d("BootStartupReceiver", "Requested notification listener rebind")
            } catch (error: Exception) {
                Log.w("BootStartupReceiver", "Failed to request notification listener rebind", error)
            }
        }

        if (edgeGlowEnabled && Settings.canDrawOverlays(context)) {
            Log.d("BootStartupReceiver", "Edge glow remains ready for on-demand use")
        }

        if (blockUnknownEnabled) {
            Log.d("BootStartupReceiver", "Call screening remains enabled and will be rebound by the system")
        }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        if (flat.isNullOrBlank()) return false

        val packageName = context.packageName
        val names = flat.split(":")
        for (name in names) {
            val componentName = ComponentName.unflattenFromString(name)
            if (componentName != null && TextUtils.equals(packageName, componentName.packageName)) {
                return true
            }
        }
        return false
    }
}
