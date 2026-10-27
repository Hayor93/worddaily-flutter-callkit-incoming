package com.hiennv.flutter_callkit_incoming

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/** State only: never records audio, identities, intent extras or credentials. */
object WordDailyAudioDiagnostics : Application.ActivityLifecycleCallbacks {
    private var installed = false
    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
    }
    private fun log(activity: Activity, event: String) {
        Log.i("WD_AUDIO", "activity=$event class=${activity.javaClass.simpleName} process=${Process.myPid()} instance=${System.identityHashCode(activity)}")
    }
    override fun onActivityCreated(a: Activity, state: Bundle?) = log(a, "created_restored_${state != null}")
    override fun onActivityStarted(a: Activity) = log(a, "started")
    override fun onActivityResumed(a: Activity) = log(a, "resumed")
    override fun onActivityPaused(a: Activity) = log(a, "paused")
    override fun onActivityStopped(a: Activity) = log(a, "stopped")
    override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = log(a, "save_state")
    override fun onActivityDestroyed(a: Activity) = log(a, "destroyed_config_${a.isChangingConfigurations}")
}

class WordDailyCaptureDiagnostics(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var callback: AudioManager.AudioRecordingCallback? = null
    fun start() {
        if (Build.VERSION.SDK_INT < 24) return
        callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val silenced = Build.VERSION.SDK_INT >= 29 && configs.any { it.isClientSilenced }
                Log.i("WD_AUDIO", "capture_config count=${configs.size} silenced=$silenced mode=${audio.mode} system_mic_muted=${audio.isMicrophoneMute}")
            }
        }
        audio.registerAudioRecordingCallback(callback!!, Handler(Looper.getMainLooper()))
    }
    fun stop() {
        if (Build.VERSION.SDK_INT >= 24) callback?.let { audio.unregisterAudioRecordingCallback(it) }
        callback = null
    }
}
