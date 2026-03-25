package com.callmanager.callmanager

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationBlockerService : NotificationListenerService() {

    private val whatsAppPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    private var isBlockingWhatsAppCall = false
    private var originalFilter: Int = -1
    private var originalRingerMode: Int = -1
    private val handler = Handler(Looper.getMainLooper())
    private val restoreRunnable = Runnable { restoreRinger() }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!whatsAppPackages.contains(sbn.packageName)) return

        val notification = sbn.notification
        val extras = notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (isBlockingWhatsAppCall && text.contains("missed call", ignoreCase = true)) {
            Log.d("WhatsAppBlocker", "Missed call detected. Restoring ringer immediately.")
            restoreRinger()
            return
        }

        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        if (!prefs.getBoolean("block_whatsapp_unknown", false)) return

        val isCall = (notification.category == Notification.CATEGORY_CALL) ||
            text.contains("Incoming voice call", ignoreCase = true) ||
            text.contains("Incoming video call", ignoreCase = true) ||
            (notification.flags and Notification.FLAG_INSISTENT != 0)

        if (isCall) {
            val callerIdentity = title.trim()
            if (callerIdentity.isNotEmpty()) {
                val cleanIdentity = callerIdentity.replace(" ", "").replace("-", "")
                val isKnown = isContact(callerIdentity) || isContact(cleanIdentity)
                val blockedNumbers = prefs.getStringSet("blocked_set", emptySet()) ?: emptySet()
                val allowedPrefixes = emptySet<String>()
                val blockedPrefixes = emptySet<String>()

                val isBlockedByPrefix = blockedPrefixes.any { cleanIdentity.startsWith(it) }
                val isAllowedByPrefix = allowedPrefixes.any { cleanIdentity.startsWith(it) }
                val isBlockedManually = blockedNumbers.contains(callerIdentity) || blockedNumbers.contains(cleanIdentity)
                val shouldBlock = isBlockedByPrefix || isBlockedManually || (!isKnown && !isAllowedByPrefix)

                if (shouldBlock) {
                    Log.d("WhatsAppBlocker", "MATCHED! Blocking WhatsApp call from: $callerIdentity")

                    if (prefs.getBoolean("edge_glow_enabled", false) && Settings.canDrawOverlays(this)) {
                        try {
                            startService(Intent(this, EdgeGlowService::class.java))
                        } catch (_: Exception) {
                        }
                    }

                    silenceRingerTemporarily()
                    hideCallScreen()

                    try {
                        snoozeNotification(sbn.key, 10000)
                    } catch (_: Exception) {
                    }

                    cancelNotification(sbn.key)
                }
            }
        }
    }

    private fun hideCallScreen() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        try {
            startActivity(intent)
            handler.postDelayed({ try { startActivity(intent) } catch (_: Exception) {} }, 300)
            handler.postDelayed({ try { startActivity(intent) } catch (_: Exception) {} }, 800)
            handler.postDelayed({ try { startActivity(intent) } catch (_: Exception) {} }, 1500)
        } catch (e: Exception) {
            Log.e("WhatsAppBlocker", "Failed to hide call screen: ${e.message}")
        }
    }

    private fun silenceRingerTemporarily() {
        try {
            if (!isBlockingWhatsAppCall) {
                if (notificationManager.isNotificationPolicyAccessGranted) {
                    val current = notificationManager.currentInterruptionFilter
                    originalFilter = if (current != NotificationManager.INTERRUPTION_FILTER_NONE) {
                        current
                    } else {
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    }
                } else {
                    originalRingerMode = audioManager.ringerMode
                }
                isBlockingWhatsAppCall = true
            }

            if (notificationManager.isNotificationPolicyAccessGranted) {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            } else {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            }

            handler.removeCallbacks(restoreRunnable)
            handler.postDelayed(restoreRunnable, 15000)
        } catch (e: Exception) {
            Log.e("WhatsAppBlocker", "Silence failed: ${e.message}")
        }
    }

    @SuppressLint("WrongConstant")
    private fun restoreRinger() {
        if (!isBlockingWhatsAppCall) return

        try {
            if (originalFilter != -1) {
                notificationManager.setInterruptionFilter(originalFilter)
            } else if (originalRingerMode != -1) {
                audioManager.ringerMode = originalRingerMode
            }
        } catch (e: Exception) {
            Log.e("WhatsAppBlocker", "Restore failed: ${e.message}")
        } finally {
            isBlockingWhatsAppCall = false
            originalFilter = -1
            originalRingerMode = -1
            handler.removeCallbacks(restoreRunnable)
        }
    }

    private fun isContact(nameOrNumber: String): Boolean {
        if (nameOrNumber.isEmpty()) return false

        val phoneUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(nameOrNumber))
        val hasPhone = try {
            contentResolver.query(
                phoneUri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }

        if (hasPhone) return true

        val nameUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(nameOrNumber))
        return try {
            contentResolver.query(
                nameUri,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        restoreRinger()
    }
}
