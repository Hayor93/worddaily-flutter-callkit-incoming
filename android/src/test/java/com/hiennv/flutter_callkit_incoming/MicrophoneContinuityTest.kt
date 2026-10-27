package com.hiennv.flutter_callkit_incoming

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34, 35])
class MicrophoneContinuityTest {
    private fun serviceLogs() = ShadowLog.getLogsForTag("CallkitNotificationService").joinToString("\n") { it.msg + " " + it.throwable }
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val id = "4a66c493-b5d0-4186-a3af-0a05c9600d28"
    @Before fun setup() { removeAllCalls(context); shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO) }
    @After fun cleanup() { removeAllCalls(context) }
    private fun data() = Data(mapOf("id" to id, "nameCaller" to "WordDaily", "type" to 0))

    @Test fun activeMicrophoneServiceIsStickyAndRestoresWithoutFlutterPlugin() {
        addCall(context, data(), true)
        val controller = Robolectric.buildService(CallkitNotificationService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(CallkitNotificationService.hasMicrophoneForeground(id))
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertEquals(serviceLogs(), Service.START_STICKY, service.onStartCommand(null, 0, 2))
        } finally { controller.destroy() }
        assertFalse(CallkitNotificationService.hasMicrophoneForeground(id))
    }

    @Test fun emptyOrUnacceptedRegistryDoesNotResurrectMicrophoneService() {
        addCall(context, data(), false)
        val controller = Robolectric.buildService(CallkitNotificationService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(null, 0, 1))
            assertFalse(CallkitNotificationService.hasMicrophoneForeground(id))
        } finally { controller.destroy() }
    }

    class ResumedActivity : Activity(), LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun promotionRequiresResumedActivityAndRuntimeMicrophonePermission() {
        val controller = Robolectric.buildActivity(ResumedActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.registry.currentState = Lifecycle.State.RESUMED
            assertTrue(CallkitNotificationService.canStartMicrophone(activity, true))
            assertFalse(CallkitNotificationService.canStartMicrophone(activity, false))
            activity.registry.currentState = Lifecycle.State.CREATED
            assertFalse(CallkitNotificationService.canStartMicrophone(activity, true))
            assertFalse(CallkitNotificationService.ensureMicrophoneForeground(context, activity, data()))
            assertTrue(getDataActiveCalls(context).isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun explicitPromotionCreatesOneForegroundCallAndCleanupClearsIt() {
        val controller = Robolectric.buildService(CallkitNotificationService::class.java).create()
        val intent = Intent(context, CallkitNotificationService::class.java)
            .setAction(CallkitNotificationService.ACTION_MICROPHONE_READY)
            .putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data().toBundle())
        try {
            assertEquals(Service.START_STICKY, controller.get().onStartCommand(intent, 0, 1))
            assertTrue(CallkitNotificationService.hasMicrophoneForeground(id))
            assertEquals(serviceLogs(), Service.START_STICKY, controller.get().onStartCommand(intent, 0, 2))
            assertFalse(CallkitNotificationService.hasMicrophoneForeground("another-session"))
        } finally { controller.destroy() }
        assertFalse(CallkitNotificationService.hasMicrophoneForeground(id))
    }

    @Test fun grantedPermissionUpgradesPhoneOnlyServiceWithoutLosingForeground() {
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildService(CallkitNotificationService::class.java).create()
        val intent = Intent(context, CallkitNotificationService::class.java)
            .setAction(CallkitNotificationService.ACTION_MICROPHONE_READY)
            .putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data().toBundle())
        try {
            assertEquals(Service.START_STICKY, controller.get().onStartCommand(intent, 0, 1))
            assertFalse(CallkitNotificationService.hasMicrophoneForeground(id))
            shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val result = controller.get().onStartCommand(intent, 0, 2)
            assertEquals(serviceLogs(), Service.START_STICKY, result)
            assertTrue(serviceLogs(), CallkitNotificationService.hasMicrophoneForeground(id))
        } finally { controller.destroy() }
    }
}
