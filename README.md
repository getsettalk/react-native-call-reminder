# react-native-call-reminder

[![npm](https://img.shields.io/npm/v/react-native-call-reminder.svg)](https://www.npmjs.com/package/react-native-call-reminder)
[![license](https://img.shields.io/npm/l/react-native-call-reminder.svg)](./LICENSE)

**Reminders that ring like a phone call — and stay within Google Play and App Store rules.**

A React Native (New Architecture) library that turns an important reminder into an
"incoming call": the phone rings, a full-screen, app-branded call screen appears (even over
the lock screen), and when the user answers, the reminder is **spoken aloud in their
language** with the device's text-to-speech engine. Afterwards the user picks an action
such as *I took it* or *Remind me in 10 minutes*, and your app gets the result as an event,
even if it was killed in the meantime.

- **Android** – insistent ringing notification on its own channel → full-screen call screen
  (or a heads-up with Answer / Decline when full-screen is not allowed) → text-to-speech →
  your action buttons.
- **iOS** – **Time Sensitive** notification (optionally a Critical Alert) with Answer /
  Decline → in-app call screen → `AVSpeechSynthesizer` → your action buttons.
- **Never loses an event** – `answered`, `declined`, `timeout`, `action`… are written to a
  durable native queue first and delivered to JS live, through a headless task, or on the
  next launch.

| Platform | Status in 0.1.0 |
| --- | --- |
| Android (API 24 – 36) | Production-tested end to end on a real FCM push: lock screen, background, killed process, full-screen denied, timeout, voice fallback. |
| iOS (15.1+) | Implemented and code-reviewed, **not yet built or device-tested**. Treat as beta and test on a device before shipping. |

---

## Why this library?

A normal notification is easy to miss: it makes one short sound, sits in the shade, and is
gone after a swipe. For some reminders that is not good enough — a missed dose of medicine,
a missed check-in with an elderly parent. People do not ignore a ringing phone.

Building "a call that is not a call" correctly is hard, and the obvious shortcuts get apps
rejected:

| Shortcut | Why it fails |
| --- | --- |
| Android `ConnectionService` / Telecom, `MANAGE_OWN_CALLS`, a `phoneCall` foreground service | Reserved for real VoIP / phone calls. Using them for reminders is a Play policy violation. |
| Claiming to be an alarm-clock or calling app to get `USE_FULL_SCREEN_INTENT` pre-granted | Misdeclaration in the Play Console; updates get rejected or the permission revoked. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `USE_EXACT_ALARM` | Restricted permissions with narrow allowed use cases. |
| iOS CallKit + PushKit (VoIP push) | Apple only allows them for real calls; iOS terminates apps that receive VoIP pushes without reporting a call, and App Review rejects it. |
| A fake caller name or number | Deceptive behaviour / impersonation under both stores' policies. |

This library does none of that. It uses ordinary, high-priority notifications, asks the
user for the one sensitive permission (full-screen intents on Android 14+), degrades
gracefully when it is denied, always shows **your app's name and icon**, and only speaks
after the user answers.

## Who is it for?

- **Medication and health apps** – dose reminders for patients, elderly users, or anyone
  who misses silent notifications. This is the use case it was built for.
- **Caregiving / family apps** – "Call grandma at 8 pm to take her tablets", spoken in her
  language.
- **Accessibility** – users with low vision or low literacy who understand a spoken
  reminder better than a text notification.
- **Any app with a few genuinely time-critical, user-requested reminders** – a scheduled
  check-in, a critical appointment, a time-boxed task the user explicitly asked to be
  *called* about.

## When should you use it — and when not?

**Use it when**

- the user **explicitly opted in** to being "called" for that reminder (ideally per item),
- missing the reminder has a real cost (health, safety, money),
- the reminder is rare enough that a ringing phone is welcome (a few times a day, not
  every hour), and
- you can still deliver an ordinary notification as a backup.

**Do not use it for**

- marketing, promotions, "come back to the app" nudges, streaks or social notifications —
  this is exactly the misuse the full-screen-intent policy and Time Sensitive guidelines
  are written against;
- anything the user did not ask for;
- your **only** reminder channel. Pushes can be delayed by Doze, OEM battery savers or a
  lost network. Keep your regular local reminder and treat the call as an extra layer
  (see [Recommended architecture](#recommended-architecture)).

## How it works

```
                 ┌──────────────────────────── your trigger ────────────────────────────┐
                 │  server cron → FCM push   │   in-app button   │   your own scheduler  │
                 └─────────────┬──────────────────────────────────────────────────────────┘
                               ▼
                 CallReminder.showIncomingCall({ callId, title, speakText, language, … })
                               │
         ┌─────────────────────┴───────────────────────┐
     Android                                          iOS
 insistent CATEGORY_ALARM notification        Time Sensitive notification
   ├─ full-screen allowed → call screen         with Answer / Decline actions
   │   (also over the lock screen)                     │
   └─ not allowed → sticky heads-up            Answer → app opens → in-app call screen
       with Answer / Decline                           │
               │                                       │
        Answer → stop ringing → text-to-speech in `language`
                 (falls back to `fallbackLanguage` + `fallbackSpeakText`)
               │
        action buttons (e.g. taken / snooze) → event → your JS handler → your backend
               │
        no answer within `timeoutSeconds` → `timeout` event (even after process death)
```

The library **presents** calls; it does not schedule them. *When* to ring is up to you —
which keeps it simple and lets you choose the trigger that fits your app (next section).

## Ways to use it

### 1. Server-triggered calls (recommended for reminders)

Your backend knows the schedule, the user's timezone and whether the dose was already
taken. A cron job sends a **high-priority FCM message** at the right minute:

- **Android**: a **data-only** message (`android.priority: high`, short `ttl` such as 120 s
  so a late call is dropped rather than ringing an hour late). Your
  `messaging().setBackgroundMessageHandler` calls `showIncomingCall` — this works with the
  app in the background or killed.
- **iOS**: the same message carries an `apns` alert with
  `aps.category = "CALL_REMINDER"` and `interruption-level: time-sensitive`; iOS shows it
  directly and the library picks the call up from the push keys (see
  [Remote pushes](#remote-pushes-apns-via-fcm)).

```ts
// index.js
import messaging from '@react-native-firebase/messaging';
import CallReminder from 'react-native-call-reminder';

CallReminder.registerBackgroundHandler(handleCallEvent); // module scope

messaging().setBackgroundMessageHandler(async message => {
  const d = message.data ?? {};
  if (d.type !== 'call_reminder') return; // let your other handlers deal with the rest
  await CallReminder.showIncomingCall({
    callId: String(d.call_id),
    callerName: 'Acme Health',
    title: String(d.title),
    body: String(d.body),
    speakText: String(d.speak_text),
    language: String(d.lang),            // e.g. 'hi-IN'
    fallbackLanguage: 'en-IN',
    fallbackSpeakText: String(d.fallback_speak_text ?? ''),
    timeoutSeconds: Number(d.timeout_sec ?? 45),
    payload: { callId: String(d.call_id), doseIds: String(d.dose_ids ?? '') },
  });
});
```

Server-side checklist: one call per user per minute (group items), an idempotency key so
an overlapping cron run never rings twice, skip items already completed, a small catch-up
window for a missed cron minute, and an optional retry or snooze re-call driven by the
events your app reports back.

### 2. In-app / user-initiated calls

A "Test call" button on your settings screen, a demo during onboarding, or a call started
from your own UI. Just call `showIncomingCall(...)` — no server needed.

### 3. Your own on-device trigger

If you already schedule work on the device (for example an exact alarm or a background
task that runs JS), call `showIncomingCall` from it. Keep in mind that on Android, starting
the call from a background context relies on the notification (not on launching an
activity), which is exactly what the library does, so it is allowed.

### 4. Speech only

`speak(text, { language })`, `isLanguageAvailable(tag)` and `getAvailableLanguages()` work on
their own, e.g. to let users preview the voice in your settings screen.

## Recommended architecture

```
Local reminder (always on, works offline)  ─┐
                                            ├─► user takes medicine ─► app logs it
Call reminder (opt-in, server push)        ─┘        ▲
          │                                          │
          └─ events: answered / declined / timeout / action:taken / action:snooze ──► backend
                                                     (stop retries, re-call on snooze)
```

- Keep the ordinary local notification as the guaranteed baseline.
- Gate calls behind an explicit per-item opt-in (and, if you like, a paid plan).
- Report every event to your backend so it can stop retries once the user answered or acted.

## Google Play: what to declare and how

The library merges these into your manifest: `POST_NOTIFICATIONS`,
`USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK` (only while React Native's headless task
runs), one activity, one receiver and one headless service. **No** foreground service, no
exact-alarm permission, no battery-optimisation permission.

**Before you publish an update that includes this library:**

1. **Play Console → App content → Full-screen intent permission.** Declare honestly. For a
   reminder/health app the core functionality is **not** "alarm clock" or "calling", so
   answer that it is not — the permission will not be pre-granted on Android 14+, and that
   is fine.
2. **Ask the user in context.** When they turn calls on, explain why ("so the reminder can
   ring over your lock screen") and call `requestFullScreenIntentPermission()`. It opens the
   system page and resolves with the new state when they come back.
3. **Degrade gracefully.** If the user says no, calls still ring as a sticky heads-up with
   Answer / Decline. Show a gentle hint in your settings, never block the app.
4. **Data safety form.** The library itself collects nothing. If *your app* sends call
   events (answered/declined/taken) to your server, declare that ("App activity → App
   interactions", app functionality).
5. **Health apps declaration.** If your app is a health/medical app, keep your Play Console
   Health apps declaration up to date as usual.
6. **Store listing and review notes.** Mention that users can opt in to "reminder calls"
   from your app. The call screen shows your app name and icon, never a person or a phone
   number.
7. **OEM battery managers (Xiaomi, Oppo, Vivo, …).** Guide users with
   `openAutoStartSettings()` and `openBatteryOptimizationSettings()` — these open settings
   pages and need no extra permission.

## App Store: what to do

1. Add the **Time Sensitive Notifications** capability (and Push Notifications if you use
   remote pushes). Critical Alerts only if Apple granted you the entitlement.
2. No CallKit, PushKit or VoIP background mode is needed — and you should not add them for
   this.
3. In review notes, explain that users opt in to reminder calls, and keep Time Sensitive for
   genuinely time-critical reminders.
4. The pod ships a privacy manifest for its only required-reason API (`UserDefaults`).

---

## Installation

```sh
yarn add react-native-call-reminder
cd ios && pod install
```

Autolinking registers the Android package and the iOS pod. There is nothing to add to
`MainApplication`.

## Quick start

```ts
// index.js — at module scope, before AppRegistry.registerComponent
import CallReminder from 'react-native-call-reminder';

CallReminder.registerBackgroundHandler(async event => {
  // Runs headless on Android when no listener is attached (app in background or killed).
  if (event.type === 'action' && event.actionId === 'taken') {
    await markDoseTaken(event.payload);
  }
});
```

```ts
import CallReminder from 'react-native-call-reminder';

await CallReminder.configure({
  appName: 'Acme Health',
  accentColor: '#FF0F766E',
  labels: { answer: 'Answer', decline: 'Not now', incomingTitle: 'Reminder call' },
  defaultActions: [
    { id: 'taken', label: 'I took it', style: 'primary' },
    { id: 'snooze', label: 'Remind me in 10 min', style: 'secondary' },
  ],
  ios: { sound: 'call_reminder.caf' },
});

const permissions = await CallReminder.getPermissions();
if (permissions.notifications !== 'granted') {
  await CallReminder.requestNotificationPermission();
}
if (permissions.fullScreenIntent === 'denied') {
  await CallReminder.requestFullScreenIntentPermission(); // Android 14+: opens Settings
}

const { presentation, reason } = await CallReminder.showIncomingCall({
  callId: 'dose-2024-06-01T08:00',
  callerName: 'Acme Health',
  title: 'Medicine reminder',
  body: 'Time for your 8:00 medicines',
  speakText: 'Hello! It is time to take Metformin, 500 milligrams, after breakfast.',
  language: 'hi-IN',
  fallbackLanguage: 'en-IN',
  payload: { doseId: '42' },
});

const subscription = CallReminder.addListener(event => {
  console.log(event.type, event.callId, event.actionId);
});
```

Every event is written to a native queue **before** it is delivered. When all listeners
return (or their promises resolve) without throwing, the event is acknowledged and leaves
the queue. Anything that could not be delivered stays queued: on start-up, drain it:

```ts
const pending = await CallReminder.getPendingEvents();
for (const event of pending) {
  await handle(event);
}
await CallReminder.acknowledgeEvents(pending.map(e => e.id));
```

## API

| Function | Returns | Notes |
| --- | --- | --- |
| `configure(config)` | `Promise<void>` | Persisted natively; receivers and the call screen use it even when JS is not running. Call at start-up and after copy/colour changes. |
| `getPermissions()` | `Promise<CallReminderPermissions>` | See below. |
| `requestNotificationPermission({ criticalAlerts? })` | `Promise<PermissionState>` | Android 13+: POST_NOTIFICATIONS prompt (needs a foreground activity, rejects with `no_activity` otherwise). iOS: alert/sound/badge, plus Critical Alerts when requested. |
| `requestFullScreenIntentPermission()` | `Promise<PermissionState>` | Android 14+: opens *Full-screen notifications* for the app and resolves with the re-checked state when the user returns. Resolves immediately when already granted or not applicable (`granted` below Android 14, `not_applicable` on iOS). |
| `openExactAlarmSettings()` | `Promise<PermissionState>` | Android 12+: *Alarms & reminders*. Same semantics: resolves immediately when already granted or `not_applicable` (below Android 12, or the app declares no exact-alarm permission), otherwise on return. |
| `openBatteryOptimizationSettings()` | `Promise<PermissionState>` | Android: battery-optimisation list (`granted` = app is exempt). Resolves immediately when already exempt, otherwise on return. |
| `openAutoStartSettings()` | `Promise<boolean>` | Opens the OEM auto-start manager (Xiaomi/Redmi/POCO, Oppo/Realme/OnePlus, Vivo/iQOO, Huawei/Honor, Samsung, Asus, Letv, Meizu, Nokia). `false` when none could be opened. |
| `openNotificationSettings(channelId?)` | `Promise<void>` | Android: the channel page (default: the call channel), else the app's notification settings. iOS 16+: the app's notification settings. |
| `openAppSettings()` | `Promise<void>` | |
| `showIncomingCall(options)` | `Promise<{ presentation, reason? }>` | `presentation`: `full_screen`, `heads_up`, `notification` or `suppressed`. Creates the call channel if needed. |
| `endCall(callId)` | `Promise<void>` | Ends a ringing or answered call (`ended`, reason `api`). |
| `getActiveCall()` | `Promise<{ callId, state: 'ringing' \| 'active' } \| null>` | |
| `speak(text, { language, fallbackLanguage?, rate?, pitch?, utteranceId? })` | `Promise<{ language, usedFallback }>` | Resolves when finished or stopped. Rejects with `busy` while a call is being spoken. |
| `stopSpeaking()` | `Promise<void>` | |
| `isLanguageAvailable(tag)` | `Promise<'available' \| 'missing_data' \| 'not_supported'>` | `missing_data`: Android TTS voice not downloaded. |
| `getAvailableLanguages()` | `Promise<string[]>` | BCP-47 tags. |
| `addListener(listener)` | `{ remove() }` | Live events. |
| `registerBackgroundHandler(handler)` | `void` | Receives events while no listener is attached: live whenever JS is running (also in the background, e.g. after a push woke the app). Android: also registers `AppRegistry.registerHeadlessTask('CallReminderBackgroundEvent', …)` for when JS is not running — call it at module scope in `index.js`. |
| `getPendingEvents()` / `acknowledgeEvents(ids)` | | The durable queue (max 500 events, oldest dropped first). |

`HEADLESS_TASK_NAME` (`'CallReminderBackgroundEvent'`) and `CallReminderError` (with a
`code`) are also exported. Invalid arguments reject with code `invalid_argument` before
reaching native code.

### Permissions

`getPermissions()` returns:

| Field | Android | iOS |
| --- | --- | --- |
| `notifications` | POST_NOTIFICATIONS + app switch | authorization status |
| `channel` | call channel exists, enabled, importance HIGH (`not_determined` until `configure()` / `showIncomingCall()` created it) | `not_applicable` |
| `fullScreenIntent` | API 34+: `canUseFullScreenIntent()`; older: `granted` | `not_applicable` |
| `exactAlarm` | API 31+: `canScheduleExactAlarms()`; older: `granted`; `not_applicable` when the app declares neither `SCHEDULE_EXACT_ALARM` nor `USE_EXACT_ALARM` | `not_applicable` |
| `batteryOptimization` | `granted` when exempt | `not_applicable` |
| `autoStart` / `oemHasAutoStartManager` | `unknown` when the OEM ships a manager (its state cannot be read), else `not_applicable` | `not_applicable` / `false` |
| `timeSensitive` | `not_applicable` | Time Sensitive setting |
| `criticalAlerts` | `not_applicable` | Critical Alerts setting |
| `lockScreen` | notifications shown on the lock screen | lock-screen setting |
| `allRequiredGranted` | notifications + channel + full-screen intent | notifications |

Plus `platform`, `osVersion`, `sdkInt` (Android) and `manufacturer`.

Reading permissions never creates notification channels, so call `configure()` first (a
channel's sound and vibration are frozen when it is created; one created with default
settings before your `configure()` would linger in the system settings forever).
`openNotificationSettings()` before `configure()` opens the app's notification settings.

### `configure(config)`

| Field | Default | |
| --- | --- | --- |
| `channelId`, `channelName`, `channelDescription` | `call_reminder_incoming`, "Reminder calls", … | Android channel. A channel's sound and vibration are fixed once created; use a new `channelId` to change them. |
| `ringtone` | `'default'` | Android: `'default'` (ringtone), `'alarm'`, `'notification'`, `'silent'` or a `res/raw` name (stored in the channel by name, so it survives resource-id changes in later builds — keep it from being shrunk, see *Resources named from JS*). A per-call `ringtone` gets its own channel. |
| `vibrationPattern` | `[0, 1000, 800, 1000, 800]` | Android, ms. `[]` disables vibration. |
| `ringAudioUsage` | `'alarm'` | Android: `'alarm'`, `'ringtone'` or `'notification'`. |
| `speechAudioUsage` | `'alarm'` | Android: `'alarm'` (audible with the ringer muted), `'media'` or `'accessibility'`. |
| `accentColor`, `backgroundColor`, `textColor` | blue, dark navy, white | `#RRGGBB` or `#AARRGGBB`. |
| `appName` | app label | Brand line on the call screen. |
| `smallIcon` | bell | Android drawable/mipmap name for the status bar. |
| `largeIcon` | app icon | Android drawable/mipmap or iOS asset name for the avatar. |
| `labels` | English | `answer`, `decline`, `incomingTitle`, `speaking`, `listening`, `ended`, `endCall`, `replay`, `tapToAnswer`. |
| `defaultActions` | none | Buttons shown after answering when a call has no `actions`. Up to 4. |
| `duplicateWindowSeconds` | `60` | A second `showIncomingCall` with the same `callId` within this window is `suppressed`. |
| `idleTimeoutSeconds` | `60` | An answered call ends (`ended`, reason `idle`) this long after speech finished without an action. |
| `ios.categoryId` | `CALL_REMINDER` | Notification category (must match `aps.category` of remote pushes). |
| `ios.threadId` | `call-reminder` | |
| `ios.answerTitle`, `ios.declineTitle` | labels | Notification action titles. |
| `ios.sound` | `'default'` | A bundled file (e.g. `call_reminder.caf`), `'default'` or `'silent'`. Unknown files fall back to the default sound. |
| `ios.interruptionLevel` | `'timeSensitive'` | `'passive'`, `'active'`, `'timeSensitive'` or `'critical'` (used only when the app has the entitlement **and** the user allowed Critical Alerts; otherwise Time Sensitive). |
| `ios.criticalVolume` | `1` | |
| `ios.presentCallScreen` | `true` | Set `false` to render your own screen from the `answered` event (speech still starts natively). |
| `ios.remotePayloadKeys` | see below | Keys used to read a call from a remote push. |
| `ios.remoteActions` | none | Per-push actions for remote calls: `[{ when: { is_test: '1' }, actions: [...] }]`. The first rule whose `when` values all equal the push's wins; otherwise `defaultActions`. |
| `ios.remoteEventPayloadKeys` | all but the text keys | Keys of a remote push copied into event `payload`s. By default every custom key except the call's wording (`title`, `body`, `speak_text`, `fallback_speak_text` keys), because queued events are stored on disk. |
| `ios.requiredRemotePayload` | none | e.g. `{ user_id: ['42'] }`: remote pushes whose values don't match are ignored (no call screen, speech or events; the banner is removed). Use it to ignore pushes for an account that signed out. A missing key counts as `''`. |

### `showIncomingCall(options)`

`callId`, `callerName`, `title`, `body`, `speakText`, `language` (BCP-47) are required.
Optional: `fallbackLanguage`, `fallbackSpeakText` (spoken instead of `speakText` when the
device has no voice for `language` and a fallback voice is used — text in one script read by
another language's voice is unintelligible, so pass the same message in the fallback
language), `speechRate` (1 = normal), `pitch`, `repeatSpeech` (1–5), `timeoutSeconds`
(default 45, 10–300), `actions`, `payload` (string map echoed on every event), `avatarUri`
(local file/content/resource URI or resource name), `ringtone`, `lockScreenPrivacy`.

`lockScreenPrivacy: 'private'` (Android): on a secure, locked device the notification *and*
the full-screen call screen show only your app's name and icon; Answer first asks the user
to unlock (Android 8+; on 7.x the details appear once the device is unlocked), then shows
the details. The reminder itself is still **spoken aloud** after answering, so `private`
keeps the screen private, not the audio. iOS follows the user's *Show Previews* setting.

Actions: `{ id, label, style?: 'primary' | 'secondary' | 'destructive', dismissesCall?: boolean (default true), opensApp?: boolean }`.

Presentation `reason`s: `app_foreground`, `device_in_use`, `full_screen_intent_denied`,
`channel_importance_low`, `in_phone_call`, `busy` (another reminder call is in progress),
`duplicate`, `notifications_disabled`, `channel_disabled`, `post_failed` (iOS),
`time_sensitive`, `time_sensitive_disabled`, `critical`, `active`, `passive`, `alerts_disabled` (iOS).

### Events

`{ id, type, callId, payload, timestamp, actionId?, presentation?, reason?, error? }`

| `type` | When | `reason` |
| --- | --- | --- |
| `shown` | The call was presented | presentation reason |
| `answered` | Answer pressed | |
| `declined` | Decline pressed or notification swiped away | `dismissed` when swiped |
| `timeout` | Not answered within `timeoutSeconds` (also delivered after process death). Also ends the quiet notification posted while the user was on a phone call. | |
| `action` | An action button was pressed (`actionId`) | |
| `speech_started` / `speech_done` / `speech_error` | Reminder speech progress (`error` set on failure; Android reports `speech_timeout` when the TTS engine stops responding) | `fallback_language:<tag>` when a fallback voice is used |
| `ended` | An answered call ended | `user`, `action`, `idle`, `api`, `replaced`, `process_death` |

Speech events of a standalone `speak()` have an empty `callId` (and `payload.utteranceId`)
and are delivered live only, never queued.

## Android

### What the library adds to your manifest

`POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK` (held by React
Native's `HeadlessJsTaskService` only while a background event task runs), the call
activity (own task, excluded from recents, shown over the lock screen), a non-exported
receiver and the headless service. `<queries>` entries make the TTS engine and OEM
auto-start managers visible on Android 11+. Nothing is exported.

### Full-screen intents and Google Play

Since Android 14, `USE_FULL_SCREEN_INTENT` is only granted by default to apps whose core
functionality is an alarm clock or receiving calls.

1. **Every** app targeting API 34+ that ships this permission — it is merged into your
   manifest from this library — must complete Play Console → *App content* →
   **Full-screen intent permission**. Unless your app's core functionality really is an
   alarm clock or receiving calls, answer that it is **not**, so the permission is not
   pre-granted. Claiming one of those categories for reminders is a misdeclaration and
   can get the update rejected or the permission revoked.
2. Ask the user instead: `requestFullScreenIntentPermission()` opens the system page
   (`Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`) and resolves when they come back.
3. Without it, the call still rings: the notification always requests full screen, and the
   system turns that into a sticky, expanded heads-up with Answer/Decline for about 60
   seconds (`presentation: 'heads_up'`, `reason: 'full_screen_intent_denied'`).

The notification uses `CATEGORY_ALARM`, `FLAG_INSISTENT` and `setTimeoutAfter`, and the
timeout is backed by an `AlarmManager` alarm (exact when `canScheduleExactAlarms()`, else
allow-while-idle), so `timeout` is reported even if the process died. The library never
declares `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`; if your app already holds exact-alarm
access, timeouts are to-the-second.

### Do Not Disturb

Calls ring like an alarm: the notification uses `CATEGORY_ALARM` and, by default, the
channel sound uses `USAGE_ALARM` (`ringAudioUsage: 'alarm'`). Android lets alarms through
Do Not Disturb and Bedtime mode by default, so **a reminder call rings during DND** unless
the user blocks alarms there. Tell your users (e.g. on your settings screen), or use
`ringAudioUsage: 'ringtone'` / `'notification'` to follow DND like ordinary calls and
notifications.

### OEM notes

Aggressive OEM battery managers can delay or drop high-priority FCM messages and kill the
process. Guide users through:

- **Xiaomi / Redmi / POCO (MIUI/HyperOS):** *Autostart* (`openAutoStartSettings()`), battery
  saver → *No restrictions*, and in *Other permissions* enable **Show on lock screen** and
  **Display pop-up windows while running in the background** (`openAppSettings()`).
- **Oppo / Realme / OnePlus (ColorOS/OxygenOS):** *Allow auto launch* and *Allow background activity*.
- **Vivo / iQOO:** *Background power consumption* → allow; *Autostart*.
- **Huawei / Honor:** *App launch* → manage manually, allow all three toggles.
- **Samsung:** remove the app from *Sleeping apps* / *Deep sleeping apps* (`openBatteryOptimizationSettings()` / `openAutoStartSettings()`).

`getPermissions().oemHasAutoStartManager` tells you whether such a screen exists.

### Overriding resources

Every resource is prefixed `callreminder_` and can be redefined in your app (including
translations in `values-xx/`): strings `callreminder_label_*`, `callreminder_channel_*`;
colours `callreminder_background`, `callreminder_accent`, `callreminder_text`,
`callreminder_answer`, `callreminder_decline`; drawables `callreminder_ic_notification`,
`callreminder_ic_call`, `callreminder_ic_call_end`, `callreminder_ic_replay`; theme
`CallReminder.Theme.Incoming`. Values passed to `configure()` take precedence.

R8/ProGuard consumer rules are bundled.

### Resources named from JS

`ringtone`, `smallIcon` and `largeIcon` name resources (`res/raw/…`, `drawable/…`,
`mipmap/…`) that are referenced only from your JS bundle. With `shrinkResources true`, the
resource shrinker cannot see those references and removes the files: the call then rings
with the device ringtone (frozen into the channel for good) and shows a default icon. Keep
them explicitly, e.g. `android/app/src/main/res/raw/keep.xml`:

```xml
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@raw/my_ring,@drawable/ic_brand,@mipmap/ic_launcher_round" />
```

## iOS

### Capabilities

- **Time Sensitive Notifications** – add the capability in Xcode (entitlement
  `com.apple.developer.usernotifications.time-sensitive`). Without it, notifications are
  delivered at the `active` level and do not break through Focus.
- **Critical Alerts** (optional) – requires an entitlement granted by Apple
  (`com.apple.developer.usernotifications.critical-alerts`). Only then set
  `ios.interruptionLevel: 'critical'` and call `requestNotificationPermission({ criticalAlerts: true })`.
- No background modes, CallKit or PushKit are used.

### Routing notification responses

The library never replaces `UNUserNotificationCenter.delegate`. Notifee / notify-kit and
Firebase Messaging both install a delegate that forwards notifications they do not own to
the delegate that existed when they started. Make your `AppDelegate` that delegate (before
React Native starts) and forward to `CallReminderNotifications`:

```swift
import UserNotifications
import react_native_call_reminder

@main
class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    // Must run before React Native (and therefore Notifee/Firebase) starts.
    UNUserNotificationCenter.current().delegate = self
    CallReminderNotifications.register()
    // … start React Native as usual
    return true
  }

  func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    didReceive response: UNNotificationResponse,
    withCompletionHandler completionHandler: @escaping () -> Void
  ) {
    if CallReminderNotifications.handle(response, completionHandler: completionHandler) { return }
    completionHandler()
  }

  func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    if CallReminderNotifications.willPresent(notification, completionHandler: completionHandler) { return }
    // Other notifications. Firebase Messaging (@react-native-firebase/messaging) calls the
    // completion handler itself after forwarding here, with its own foreground options —
    // leave it alone when it is in the chain. Notifee / notify-kit does NOT complete it for
    // notifications it does not own: without Firebase Messaging (or with no other delegate
    // at all), complete it yourself. Notify-kit forwards a one-shot handler, so calling it
    // here is harmless even if something else calls it too.
    completionHandler([.banner, .list, .sound])
  }
}
```

`handle` and `willPresent` return `false` (without calling the handler) for anything that
is not a call reminder.

Answer is a foreground action: iOS opens the app (after unlock), the in-app call screen
appears and the reminder is spoken (`.playback` + `.duckOthers`, audible with the silent
switch on; the previous audio session category is restored afterwards). An explicit
Answer/Decline always wins, even if the push had already been reported as `timeout`.

While the app is **open**, a call is presented without a notification sound (the call
screen appears instead) and its bundled ring sound is looped in-app, so Answer and Decline
stop it at once — a notification sound cannot be stopped once it plays and would run over
the spoken reminder. The in-app ring follows the Ring/Silent switch like a notification
sound (Critical Alert calls excepted). With `ios.sound: 'default'` (no bundled file) the
short default notification sound is used. Decline and
dismissal are handled without bringing the app forward; the events wait in the durable
queue until JS reads them with `getPendingEvents()`. Remote call pushes that were never
answered are reported as `timeout` the next time the app becomes active.

### Remote pushes (APNs via FCM)

Send an alert push with `aps.category` equal to `ios.categoryId` (default
`CALL_REMINDER`), `aps["interruption-level"] = "time-sensitive"`, `apns-priority: 10`,
`apns-push-type: alert`, and the call as top-level custom keys:

| Key (override with `ios.remotePayloadKeys`) | Field | Fallback |
| --- | --- | --- |
| `call_id` | `callId` | request identifier |
| `caller_name` | `callerName` | `appName` |
| `title` / `body` | `title` / `body` | `aps.alert` |
| `speak_text` | `speakText` | body |
| `lang` / `fallback_lang` | `language` / `fallbackLanguage` | device language |
| `fallback_speak_text` | `fallbackSpeakText` | none (`speakText` is read by the fallback voice) |
| `speech_rate` | `speechRate` | 1 |
| `timeout_sec` | `timeoutSeconds` | 45 |

Other custom string keys (except `aps`, `gcm.*`, `google.*` and the wording keys above —
see `ios.remoteEventPayloadKeys`) become the event `payload`. Per-push actions come from
`ios.remoteActions`; pushes can be restricted with `ios.requiredRemotePayload`.
You do not need to call `showIncomingCall` for these pushes on iOS; if you do (e.g. from
`onMessage`), the same `callId` is de-duplicated.

**Sound:** `aps.sound` must name a file in the app bundle (or `Library/Sounds`), in
`.caf`/`.aiff`/`.wav` format and at most 30 seconds long, e.g. `call_reminder.caf`. If the
file is not bundled iOS plays the default sound, so either bundle it or send `"default"`.

### App Store notes

The library uses standard notifications only (no CallKit/PushKit/VoIP), shows the app's
own name and icon, and speaks only after the user taps. Mention the reminder calls in your
review notes and keep the Time Sensitive level for genuinely time-critical reminders, as
the user can turn Time Sensitive delivery off per app.

The pod ships a privacy manifest (`PrivacyInfo.xcprivacy`, in the
`react-native-call-reminder_privacy` resource bundle) declaring its only required-reason
API, `UserDefaults` (reason `CA92.1`); it collects no data and does no tracking.

## Example: native-only checks

```ts
const language = 'ta-IN';
if ((await CallReminder.isLanguageAvailable(language)) !== 'available') {
  // Android: suggest installing the voice data; otherwise rely on fallbackLanguage.
}
```

## Testing with Jest

The module looks up its native TurboModule on import, which fails under Jest. A mock of the
whole public API (`jest.fn()`s with neutral results) is included:

```js
jest.mock('react-native-call-reminder', () => require('react-native-call-reminder/jest/mock'));
```

## Contributing & releasing

Source: <https://github.com/getsettalk/react-native-call-reminder->. Issues and pull
requests are welcome.

`main`, `module`, `types` and `exports` point into `lib/`, which is built, not committed.

1. `yarn install` in a clean checkout of this repository (never inside an app, so its
   `node_modules` never sits next to an app's React Native).
2. `yarn typecheck && yarn test`.
3. Update `CHANGELOG.md` and the version, commit, and tag `vX.Y.Z` (the podspec uses the
   tag as its `source`).
4. `npm publish`. `prepack` builds `lib/` with `bob build` and refuses to pack when the
   build output is missing.

## License

MIT © Sujeet Kumar
