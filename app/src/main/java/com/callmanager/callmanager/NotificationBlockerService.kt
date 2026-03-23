package com.callmanager.callmanager

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationBlockerService : NotificationListenerService() {

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
        val packageName = sbn.packageName
        if (packageName != "com.whatsapp" && packageName != "com.whatsapp.w4b") return

        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val tickerText = notification.tickerText?.toString() ?: ""

        Log.d("NotificationBlocker", "Posted: $packageName | Title: $title | Text: $text")

        // Detect missed call to restore ringer
        val isMissedCall = text.contains("missed call", ignoreCase = true) ||
                           subText.contains("missed call", ignoreCase = true) ||
                           tickerText.contains("missed call", ignoreCase = true)

        if (isBlockingWhatsAppCall && isMissedCall) {
            restoreRinger()
            return 
        }

        val prefs = getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("block_whatsapp_unknown", false)) return
        
        // Detection for incoming calls (WhatsApp & WhatsApp Business)
        val isCall = (notification.category == Notification.CATEGORY_CALL) ||
                     text.contains("voice call", ignoreCase = true) ||
                     text.contains("video call", ignoreCase = true) ||
                     text.contains("Incoming", ignoreCase = true) ||
                     (notification.flags and Notification.FLAG_INSISTENT != 0) ||
                     (notification.fullScreenIntent != null)

        if (isCall) {
            val callerIdentity = title.trim()
            if (callerIdentity.isNotEmpty()) {
                // Try both original and cleaned version for contact lookup
                val cleanIdentity = callerIdentity.replace("[^0-9+]".toRegex(), "")
                val isKnown = isContact(callerIdentity) || (cleanIdentity.length >= 10 && isContact(cleanIdentity))
                
                Log.d("NotificationBlocker", "Call from: $callerIdentity (Clean: $cleanIdentity) | isKnown: $isKnown")

                // Decision logic: Block if not in contacts
                if (!isKnown) {
                    Log.d("NotificationBlocker", "MATCHED! Blocking WhatsApp call from unknown: $callerIdentity")
                    
                    // Trigger Edge Glow if enabled and permission is present
                    if (prefs.getBoolean("edge_glow_enabled", false) && Settings.canDrawOverlays(this)) {
                        try {
                            val glowIntent = Intent(this, EdgeGlowService::class.java)
                            startService(glowIntent)
                        } catch (e: Exception) {
                            Log.e("NotificationBlocker", "Failed to start EdgeGlowService: ${e.message}")
                        }
                    }

                    silenceRingerTemporarily()
                    hideCallScreen()

                    // Try to trigger Decline action if it exists
                    val actions = notification.actions
                    if (actions != null) {
                        for (action in actions) {
                            val actionTitle = action.title.toString().lowercase()
                            if (actionTitle.contains("decline") || actionTitle.contains("hang up") ||
                                actionTitle.contains("reject") || actionTitle.contains("dismiss")) {
                                try {
                                    action.actionIntent.send()
                                    Log.d("NotificationBlocker", "Sent $actionTitle action")
                                    break
                                } catch (e: Exception) {
                                    Log.e("NotificationBlocker", "Failed to send action: ${e.message}")
                                }
                            }
                        }
                    }

                    // Always try to cancel the notification
                    cancelNotification(sbn.key)

                    // For some devices, we need to cancel by tag/id as well
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        cancelAllNotifications() // Aggressive
                    }
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
            // Repeatedly push to home to ensure WhatsApp UI stays hidden
            startActivity(intent)
            handler.postDelayed({ try { startActivity(intent) } catch (_: Exception) {} }, 300)
            handler.postDelayed({ try { startActivity(intent) } catch (_: Exception) {} }, 1000)
        } catch (e: Exception) {
            Log.e("NotificationBlocker", "Failed to hide call screen: ${e.message}")
        }
    }

    private fun silenceRingerTemporarily() {
        try {
            if (!isBlockingWhatsAppCall) {
                if (notificationManager.isNotificationPolicyAccessGranted) {
                    val current = notificationManager.currentInterruptionFilter
                    if (current != NotificationManager.INTERRUPTION_FILTER_NONE) {
                        originalFilter = current
                    } else {
                        originalFilter = NotificationManager.INTERRUPTION_FILTER_ALL
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
            handler.postDelayed(restoreRunnable, 15000) // Auto restore after 15s
        } catch (e: Exception) {
            Log.e("NotificationBlocker", "Silence failed: ${e.message}")
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
            Log.e("NotificationBlocker", "Restore failed: ${e.message}")
        } finally {
            isBlockingWhatsAppCall = false
            originalFilter = -1
            originalRingerMode = -1
            handler.removeCallbacks(restoreRunnable)
        }
    }

    private fun isContact(nameOrNumber: String): Boolean {
        if (nameOrNumber.isEmpty()) return false

        // Check by phone number
        val phoneUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(nameOrNumber))
        val hasPhone = try {
            contentResolver.query(phoneUri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { it.count > 0 } ?: false
        } catch (_: Exception) { false }

        if (hasPhone) return true

        // Check by name
        val nameUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(nameOrNumber))
        return try {
            contentResolver.query(nameUri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { it.count > 0 } ?: false
        } catch (_: Exception) { false }
    }

    override fun onDestroy() {
        super.onDestroy()
        restoreRinger()
    }
}
