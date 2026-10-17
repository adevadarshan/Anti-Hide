package com.antihide.v2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.antihide.v2.ui.AlarmRingActivity

/**
 * Lock-screen STOP surface #1.
 *
 * This path exists independently of the full-screen intent because Android 14+ lets the user deny
 * `USE_FULL_SCREEN_INTENT`. A high-priority, public, alarm-category notification with a single
 * STOP action keeps the phone findable even when the activity cannot be launched.
 */
class AlarmNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL, "Active alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown only while the alarm is ringing."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false) // the vibrator already runs independently
                setSound(null, null)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "AntiHide service", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun buildServiceNotification(): Notification =
        NotificationCompat.Builder(context, SERVICE_CHANNEL)
            .setContentTitle("AntiHide V2 — listening")
            .setContentText("Serving the private Tailscale dashboard")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()

    fun showRinging(ringingSinceEpochMs: Long) {
        val stopIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, AntiHideForegroundService::class.java).setAction(AntiHideForegroundService.ACTION_LOCAL_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fullScreen = PendingIntent.getActivity(
            context,
            1,
            Intent(context, AlarmRingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL)
            .setContentTitle("RINGING")
            .setContentText("Started ${formatAge(ringingSinceEpochMs)} ago — tap STOP to silence")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "STOP ALARM", stopIntent)
            .build()
        runCatching { manager.notify(RINGING_ID, notification) }
    }

    fun clearRinging() {
        runCatching { manager.cancel(RINGING_ID) }
    }

    private fun formatAge(startedAtEpochMs: Long): String {
        val seconds = ((System.currentTimeMillis() - startedAtEpochMs) / 1000).coerceAtLeast(0)
        return "$seconds s"
    }

    companion object {
        const val ALARM_CHANNEL = "antihide_alarm"
        const val SERVICE_CHANNEL = "antihide_slice1"
        const val RINGING_ID = 2
    }
}