package com.callmanager.callmanager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object SyncNotificationHelper {

    private const val CHANNEL_ID = "contact_sync_status"
    private const val CHANNEL_NAME = "Contact Sync"
    private const val NOTIFICATION_ID = 3101

    fun showRunning(context: Context) {
        notify(
            context = context,
            title = context.getString(R.string.sync_notification_title_running),
            message = context.getString(R.string.sync_notification_message_running),
            ongoing = true,
            progressMax = 0,
            progressCurrent = 0,
            progressIndeterminate = true
        )
    }

    fun showRunningProgress(context: Context, synced: Int, total: Int) {
        notify(
            context = context,
            title = context.getString(R.string.sync_notification_title_running),
            message = context.getString(R.string.sync_notification_message_progress, synced, total),
            ongoing = true,
            progressMax = total,
            progressCurrent = synced,
            progressIndeterminate = false
        )
    }

    fun showCompleted(context: Context) {
        cancel(context)
        notify(
            context = context,
            title = context.getString(R.string.sync_notification_title_completed),
            message = context.getString(R.string.sync_notification_message_completed),
            ongoing = false,
            progressMax = 0,
            progressCurrent = 0,
            progressIndeterminate = false
        )
    }

    fun showPaused(context: Context, message: String) {
        cancel(context)
        notify(
            context = context,
            title = context.getString(R.string.sync_notification_title_paused),
            message = message,
            ongoing = false,
            progressMax = 0,
            progressCurrent = 0,
            progressIndeterminate = false
        )
    }

    private fun notify(
        context: Context,
        title: String,
        message: String,
        ongoing: Boolean,
        progressMax: Int,
        progressCurrent: Int,
        progressIndeterminate: Boolean
    ) {
        if (!canPostNotifications(context)) return

        createChannelIfNeeded(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setProgress(progressMax, progressCurrent, progressIndeterminate)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun cancel(context: Context) {
        if (!canPostNotifications(context)) return
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.sync_notification_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
}
