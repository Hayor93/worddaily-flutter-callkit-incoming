package com.hiennv.flutter_callkit_incoming

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

class CallkitNotificationService : Service() {

    companion object {

        private const val TAG = "CallkitNotificationService"
        const val ACTION_MICROPHONE_READY = "worddaily.call.MICROPHONE_READY"
        private var foregroundCallId: String? = null
        private var microphoneForeground = false

        fun hasMicrophoneForeground(id: String): Boolean =
            id.isNotEmpty() && foregroundCallId == id && microphoneForeground

        fun canStartMicrophone(activity: Activity?, granted: Boolean): Boolean =
            granted && activity != null && !activity.isFinishing &&
                (activity as? LifecycleOwner)?.lifecycle?.currentState
                    ?.isAtLeast(Lifecycle.State.RESUMED) == true

        // Called from the visible Flutter call screen, never the FCM isolate.
        fun ensureMicrophoneForeground(context: Context, activity: Activity?, requested: Data): Boolean {
            if (hasMicrophoneForeground(requested.id)) return true
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (requested.id.isBlank() || !canStartMicrophone(activity, granted)) {
                Log.i(TAG, "[WD_AUDIO] microphone_fgs_deferred permission=$granted activity_resumed=${canStartMicrophone(activity, true)}")
                return false
            }
            val data = getDataActiveCalls(context).firstOrNull { it.id == requested.id } ?: requested
            data.isShowCallingNotification = true
            data.type = 0 // WordDaily calls are audio-only; never request camera FGS.
            addCall(context, data, true)
            return try {
                startServiceWithAction(context, ACTION_MICROPHONE_READY, data.toBundle())
                true
            } catch (e: Exception) {
                Log.w(TAG, "[WD_AUDIO] microphone_fgs_start_failed type=${e.javaClass.simpleName}")
                false
            }
        }

        private val ActionForeground = listOf(
            CallkitConstants.ACTION_CALL_START,
            CallkitConstants.ACTION_CALL_ACCEPT,
            ACTION_MICROPHONE_READY
        )


        fun startServiceWithAction(context: Context, action: String, data: Bundle?) {
            val intent = Intent(context, CallkitNotificationService::class.java).apply {
                this.action = action
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && intent.action in ActionForeground) {
                data?.let {
                    if (it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                        ContextCompat.startForegroundService(context, intent)
                    } else {
                        context.startService(intent)
                    }
                }
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CallkitNotificationService::class.java)
            context.stopService(intent)
        }

    }

    // Get notification manager dynamically to handle plugin lifecycle properly
    private fun getCallkitNotificationManager(): CallkitNotificationManager? {
        return FlutterCallkitIncomingPlugin.getInstance()?.getCallkitNotificationManager()
    }


    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "[WD_AUDIO] service_created")
        captureDiagnostics = WordDailyCaptureDiagnostics(this).also { it.start() }
    }

    private var foregroundActive = false
    private var captureDiagnostics: WordDailyCaptureDiagnostics? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bundle = intent?.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
        // Repeated visible-screen checks must not rebuild or downgrade an
        // already established microphone service. A phone-only service still
        // passes through promotion once RECORD_AUDIO has been granted.
        if (foregroundActive && microphoneForeground &&
            ((intent?.action == ACTION_MICROPHONE_READY && bundle?.getString(CallkitConstants.EXTRA_CALLKIT_ID) == foregroundCallId) ||
             (intent == null && getDataActiveCalls(this).any { it.id == foregroundCallId && it.isAccepted }))) {
            return START_STICKY
        }
        val isForegroundAction = intent?.action in ActionForeground
        val needsPlaceholder = isForegroundAction &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bundle?.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true) == true
        if (needsPlaceholder && !foregroundActive) {
            if (!showPlaceholderForegroundNotification(bundle)) {
                return START_NOT_STICKY
            }
        }

        try {
            when (intent?.action) {
                ACTION_MICROPHONE_READY -> {
                    if (bundle == null) { stopSelf(); return START_NOT_STICKY }
                    showOngoingCallNotification(bundle)
                }
                CallkitConstants.ACTION_CALL_START -> {
                    intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                        ?.let {
                            if (it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                                getCallkitNotificationManager()?.createNotificationChanel(it)
                                showOngoingCallNotification(it)
                            } else {
                                stopSelf()
                            }
                        }
                }

                CallkitConstants.ACTION_CALL_ACCEPT -> {
                    intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                        ?.let {
                            getCallkitNotificationManager()?.clearIncomingNotification(it, true)
                            if (it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                                showOngoingCallNotification(it)
                            } else {
                                stopSelf()
                            }
                        }
                }

                null -> {
                    // OS restarted the service after kill (START_STICKY with null intent).
                    // Flutter engine may not be running yet — create a standalone manager
                    // using only Context so we can restore startForeground() without the plugin.
                    val activeCalls = getDataActiveCalls(this)
                    val bundle = activeCalls.firstOrNull { it.isAccepted && it.isShowCallingNotification }?.toBundle()
                    if (bundle != null) {
                        val pluginManager = getCallkitNotificationManager()
                        val manager = pluginManager
                            ?: CallkitNotificationManager(this, CallkitSoundPlayerManager(this))
                        manager.createNotificationChanel(bundle)
                        val notification = manager.getOnGoingCallNotification(bundle, false)
                        if (notification != null) {
                            val typeCall = bundle.getInt(CallkitConstants.EXTRA_CALLKIT_TYPE, -1)
                            startForeground(
                                notification.id,
                                notification.notification,
                                typeCall > 0
                            )
                            foregroundCallId = bundle.getString(CallkitConstants.EXTRA_CALLKIT_ID)
                            if (pluginManager == null) manager.destroy()
                        } else {
                            if (pluginManager == null) manager.destroy()
                            stopSelf()
                        }
                    } else {
                        stopSelf()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show the ongoing call notification", e)
            if (needsPlaceholder || intent == null) {
                foregroundActive = false
                microphoneForeground = false
                foregroundCallId = null
                stopSelf()
            }
        }
        Log.i(TAG, "[WD_AUDIO] service_start foreground=$foregroundActive microphone=$microphoneForeground restarted=${intent == null}")
        return if (foregroundActive) START_STICKY else START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun showOngoingCallNotification(bundle: Bundle) {

        val sharedManager = getCallkitNotificationManager()
        val manager = sharedManager ?: CallkitNotificationManager(this, null)
        manager.createNotificationChanel(bundle)
        val callkitNotification = manager.getOnGoingCallNotification(bundle, false)
        if (callkitNotification != null) {
            val typeCall = bundle.getInt(CallkitConstants.EXTRA_CALLKIT_TYPE, -1)
            startForeground(
                callkitNotification.id,
                callkitNotification.notification,
                typeCall > 0
            )
            foregroundCallId = bundle.getString(CallkitConstants.EXTRA_CALLKIT_ID)
        }
        if (sharedManager == null) manager.destroy()
    }

    private fun getOnGoingNotificationId(bundle: Bundle): Int {
        return bundle.getString(
            CallkitConstants.EXTRA_CALLKIT_CALLING_ID,
            bundle.getString(CallkitConstants.EXTRA_CALLKIT_ID, "callkit_incoming")
        ).hashCode()
    }

    private fun showPlaceholderForegroundNotification(bundle: Bundle?): Boolean {
        val notificationId = bundle?.let { getOnGoingNotificationId(it) }
            ?: "callkit_incoming".hashCode()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (notificationManager?.getNotificationChannel(CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING) == null) {
                notificationManager?.createNotificationChannel(
                    NotificationChannel(
                        CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING,
                        "Ongoing Call",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        val smallIcon =
            applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_call_incoming

        val notification =
            androidx.core.app.NotificationCompat.Builder(
                this,
                CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING
            )
                .setContentTitle(applicationInfo.loadLabel(packageManager))
                .setContentText("Calling...")
                .setSmallIcon(smallIcon)
                .setOngoing(true)
                .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else {
                startForeground(notificationId, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground with the placeholder notification", e)
            stopSelf()
            false
        }
    }

    private fun startForeground(notificationId: Int, notification: Notification, isVideo: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Since Android 14 (API 34), starting a foreground service with the
                // MICROPHONE or CAMERA type throws a SecurityException unless the
                // corresponding runtime permission is already granted. Only add the
                // types whose permission is granted (e.g. the user may answer a call
                // before the app ever requested RECORD_AUDIO).
                if (isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
                    mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (isVideo && isPermissionGranted(Manifest.permission.CAMERA)) {
                    mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            startForeground(notificationId, notification, mask)
            microphoneForeground = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                (mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
        } else {
            startForeground(notificationId, notification)
            microphoneForeground = isPermissionGranted(Manifest.permission.RECORD_AUDIO)
        }
        foregroundActive = true
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED


    override fun onDestroy() {
        captureDiagnostics?.stop()
        captureDiagnostics = null
        foregroundActive = false
        microphoneForeground = false
        foregroundCallId = null
        Log.i(TAG, "[WD_AUDIO] service_destroyed")
        super.onDestroy()
        // Don't destroy the notification manager here as it's shared across the app
        // The plugin will handle cleanup when all engines are detached
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Don't kill the FGS. The app might be closed by user but the call is still ongoing
    }
}
