# WordDaily Android call-channel patch

This fork retains the 3.1.6 Dart/iOS API and modifies Android only.

- `worddaily_call_notifications_v1` — Call notifications, HIGH, default Android call ringtone, ringtone audio usage, vibration and public lock-screen visibility.
- `callkit_ongoing_channel_id` — Ongoing Call, LOW, no default sound/vibration; existing Hang up remains.
- `callkit_missed_channel_id` — Missed Calls, HIGH, default notification sound/vibration.

All channels register on Flutter engine attachment, including background engines. Incoming notifications use the new channel. Android 8+ uses channel-owned repeated notification audio (`FLAG_INSISTENT`), with the separate native ringtone player disabled to prevent two competing sound streams. Before Android 8 the existing player remains. Incoming/missed/ongoing keep their existing per-session IDs and cleanup; updated avatar notifications use the same ID. Foreground and locked-screen calls use the existing notification full-screen PendingIntent instead of bypassing notification creation through direct Activity launch. Android permissions, ringer state and DND remain authoritative.

The old incoming channel deliberately had `sound=null`, so changing its name cannot add a sound. A one-time migration copies blocked/user-selected importance, vibration, and (Android 11+) explicit user sound selections; otherwise a new incoming channel gets the default call ringtone. On Android 8–10 explicit user-selected silence cannot be distinguished from the plugin's silent default, so existing silence is preserved conservatively and must be changed in Android settings. Old incoming IDs are deleted only when no notification uses them, and migration is remembered. Existing new-channel settings are never reset on launch. Ongoing/missed channel settings are retained.

Method-channel additions on `flutter_callkit_incoming`:
- `getCallNotificationChannelInfo`: actual channel IDs/names/importance/sound-enabled/vibration flags, app notification state and full-screen access. Does not return user-selected sound URIs or call/user data.
- `openCallNotificationSettings` with `category=incoming|ongoing|missed`: open that Android category directly.

## Validation gate

This patch has not been compiled or run on a physical Android device in this workspace (Android SDK unavailable). Native source review and diff checks are not proof of audible ringing. Before release verify visible categories after first app launch, selected sound/vibration affecting calls, reboot persistence, foreground/background/locked presentation, no double ring, Accept/Decline/timeout/cancellation cleanup and active-call Hang up. Check channel selection on an upgrade from the old silent channel as well as a fresh install. Test avatar refreshes while ringing because notification updates may restart system-owned ringing.

The caller's name, WordDaily app name, call UUID/session data, FCM background pipeline, iOS code and LiveKit are unchanged by the native patch. Existing plugin logs outside the changed files have not been audited.

References: https://developer.android.com/reference/android/app/Notification#FLAG_INSISTENT and https://developer.android.com/reference/android/app/NotificationChannel .

Telecom onSilence preserves the incoming invitation and actions while silencing only its matching Android notification. A later avatar update retains that silenced state. This behavior needs physical-device testing alongside volume-key and headset controls.
