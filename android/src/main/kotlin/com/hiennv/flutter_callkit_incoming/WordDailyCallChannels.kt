package com.hiennv.flutter_callkit_incoming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log

/** Channel defaults are installed once; Android owns subsequent user choices. */
internal object WordDailyCallChannels {
    const val INCOMING = "worddaily_call_notifications_v1"
    private val legacyIncoming = listOf("callkit_incoming_channel_id_v2", "callkit_incoming_channel_id")

    fun info(context: Context): Map<String, Any?> {
        ensure(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return mapOf("supported" to false)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ids = listOf(INCOMING, CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING,
            CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_MISSED)
        return mapOf("supported" to true,
            "notificationsEnabled" to androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled(),
            "fullScreenAllowed" to if (Build.VERSION.SDK_INT >= 34) manager.canUseFullScreenIntent() else true,
            "channels" to ids.mapNotNull { id -> manager.getNotificationChannel(id)?.let { channel ->
                mapOf("id" to id, "name" to channel.name.toString(), "importance" to channel.importance,
                    "soundEnabled" to (channel.sound != null), "vibrationEnabled" to channel.shouldVibrate())
            } })
    }

    fun openSettings(context: Context, category: String) {
        val id = when (category) {
            "ongoing" -> CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING
            "missed" -> CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_MISSED
            else -> INCOMING
        }
        ensure(context)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, id)
        } else {
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}"))
        }
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Synchronized
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(INCOMING)
            if (existing == null) {
                val old = legacyIncoming.mapNotNull { manager.getNotificationChannel(it) }.firstOrNull()
                val importance = when {
                    old == null -> NotificationManager.IMPORTANCE_HIGH
                    old.importance == NotificationManager.IMPORTANCE_NONE -> NotificationManager.IMPORTANCE_NONE
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && old.hasUserSetImportance() -> old.importance
                    else -> NotificationManager.IMPORTANCE_HIGH
                }
                val sound = when {
                    old == null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && old.hasUserSetSound() -> old.sound
                    // Before API 30 explicit user silence cannot be distinguished
                    // from the old plugin default. Preserve it conservatively.
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R && old.sound == null -> null
                    else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                }
                val audio = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                manager.createNotificationChannel(NotificationChannel(INCOMING, "Call notifications", importance).apply {
                    description = "Incoming WordDaily calls"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(sound, audio)
                    vibrationPattern = old?.vibrationPattern ?: longArrayOf(0, 1000, 500, 1000, 500)
                    enableVibration(old?.shouldVibrate() ?: true)
                })
            } else if (existing.name.toString() != "Call notifications") {
                existing.name = "Call notifications"
                manager.createNotificationChannel(existing) // name only; no sound/importance reset
            }
            if (manager.getNotificationChannel(CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING) == null) {
                manager.createNotificationChannel(NotificationChannel(
                    CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING, "Ongoing Call", NotificationManager.IMPORTANCE_LOW
                ).apply { setSound(null, null); enableVibration(false) })
            }
            if (manager.getNotificationChannel(CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_MISSED) == null) {
                manager.createNotificationChannel(NotificationChannel(
                    CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_MISSED, "Missed Calls", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 1000)
                })
            }
            // Retire the old silent category only once and never while it hosts
            // a still-visible invitation from an older engine/build.
            val prefs = context.getSharedPreferences("worddaily_call_channels", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("incoming_v1_migrated", false) &&
                manager.activeNotifications.none { legacyIncoming.contains(it.notification.channelId) }) {
                legacyIncoming.forEach { manager.deleteNotificationChannel(it) }
                prefs.edit().putBoolean("incoming_v1_migrated", true).commit()
            }
            Log.i("WD_CALL", "CALL_CHANNELS_READY")
        } catch (error: Exception) {
            // Channel diagnostics may fail, but must not crash calling.
            Log.w("WD_CALL", "CALL_CHANNEL_SETUP_FAILED: ${error.javaClass.simpleName}")
        }
    }
}
