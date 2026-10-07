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

| Platform | Status |
| --- | --- |
| Android (API 24 – 36) | Production-tested end to end on a real FCM push: lock screen, background, killed process, full-screen denied, timeout, voice fallback. |
| iOS (15.1+) | Implemented and code-reviewed, **not yet built or device-tested**. Treat as beta and test on a device before shipping. |

0.2.0 adds swipe-to-answer on the call screen and the `batteryUsage` / `backgroundRestricted`
permission fields (see [CHANGELOG](./CHANGELOG.md)); test the new call-screen gesture on your
devices before shipping. 0.3.0 adds [`getDiagnostics()`](#diagnostics): one call that explains
why reminders might not reach a phone, for debugging users' devices remotely.

## Screenshots

<p align="center">
  <img src="https://raw.githubusercontent.com/getsettalk/react-native-call-reminder-/main/docs/images/android.png" alt="Android: full-screen ringing call with swipe up to answer or decline, the answered screen speaking the reminder with I took it / Remind me later actions, and the heads-up fallback when full-screen is not allowed" width="900">
</p>
<p align="center">
  <img src="https://raw.githubusercontent.com/getsettalk/react-native-call-reminder-/main/docs/images/ios.png" alt="iPhone: Time Sensitive notification with Answer and Decline on the lock screen, and the in-app call screen speaking the reminder" width="620">
</p>

<sub>Illustrative mockups rendered from HTML (<code>docs/mockups</code>, <code>scripts/render-mockups.sh</code>), not device captures. Your colours, icon, labels and actions come from <code>configure()</code> and <code>showIncomingCall()</code>.</sub>

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

<p align="center">
  <img src="https://raw.githubusercontent.com/getsettalk/react-native-call-reminder-/main/docs/images/flow.png" alt="Flow: your server triggers at dose time, FCM/APNs delivers a high-priority push, showIncomingCall rings full screen or heads-up, the user answers and the reminder is spoken, then events are reported back to your app" width="900">
</p>

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
   pages and need no extra permission (the library never declares
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

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
  answerGesture: 'swipe', // default: drag Answer/Decline up; 'tap' to answer with a tap
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
if (permissions.batteryUsage === 'restricted') {
  // Only "Restricted" holds calls back; the default "Optimized" is fine.
  await CallReminder.openBatteryOptimizationSettings();
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
| `getDiagnostics()` | `Promise<CallReminderDiagnostics>` | Everything that can stop a call from reaching the device (permissions, battery/standby, DND, all notification channels, recent process deaths, OEM skin, TTS…). Read-only, needs no permission, never rejects for a field it cannot read. See [Diagnostics](#diagnostics). |
| `requestNotificationPermission({ criticalAlerts? })` | `Promise<PermissionState>` | Android 13+: POST_NOTIFICATIONS prompt (needs a foreground activity, rejects with `no_activity` otherwise). iOS: alert/sound/badge, plus Critical Alerts when requested. |
| `requestFullScreenIntentPermission()` | `Promise<PermissionState>` | Android 14+: opens *Full-screen notifications* for the app and resolves with the re-checked state when the user returns. Resolves immediately when already granted or not applicable (`granted` below Android 14, `not_applicable` on iOS). |
| `openExactAlarmSettings()` | `Promise<PermissionState>` | Android 12+: *Alarms & reminders*. Same semantics: resolves immediately when already granted or `not_applicable` (below Android 12, or the app declares no exact-alarm permission), otherwise on return. |
| `openBatteryOptimizationSettings()` | `Promise<PermissionState>` | Android: the most specific page where the user can change the app's battery usage — the app's own *Battery* page where the device has one, else the app's details page, else the battery-optimisation list (see [Battery usage](#battery-usage)). Resolves with the re-checked `batteryOptimization` on return (`denied` only while background-restricted); resolves immediately, opening nothing, when `batteryUsage` is already `unrestricted`. iOS: `not_applicable`. |
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
| `batteryOptimization` | `denied` only when background-restricted, else `granted` (also when *optimized*). **Changed in 0.2.0**, see [Battery usage](#battery-usage) | `not_applicable` |
| `batteryUsage` | `unrestricted`, `optimized`, `restricted` or `unknown` | `not_applicable` |
| `backgroundRestricted` | API 28+: `ActivityManager.isBackgroundRestricted()`; older: `false` | `false` |
| `autoStart` / `oemHasAutoStartManager` | `unknown` when the OEM ships a manager (its state cannot be read), else `not_applicable` | `not_applicable` / `false` |
| `timeSensitive` | `not_applicable` | Time Sensitive setting |
| `criticalAlerts` | `not_applicable` | Critical Alerts setting |
| `lockScreen` | notifications shown on the lock screen | lock-screen setting |
| `allRequiredGranted` | notifications + channel + full-screen intent | notifications |

Plus `platform`, `osVersion`, `sdkInt` (Android) and `manufacturer`.

#### Battery usage

Android has three battery settings per app. Only one of them is a problem for reminder calls:

| `batteryUsage` | Android setting | Calls | `batteryOptimization` |
| --- | --- | --- | --- |
| `optimized` | The default. Android 14/15: *Allow background usage* **on** (Optimized); 12–13: *Optimized* | Ring on time: high-priority FCM messages are delivered at once even in Doze | `granted` |
| `unrestricted` | *Allow background usage* → *Unrestricted* (exempt from battery optimisation) | Ring on time | `granted` |
| `restricted` | Android 14/15: *Allow background usage* **off**; 12–13: *Restricted*; 9–11: *Background restriction* on (`ActivityManager.isBackgroundRestricted()`) | May arrive late or not at all | `denied` |

So show a warning only for `batteryUsage === 'restricted'` (or `backgroundRestricted`), and
offer `openBatteryOptimizationSettings()` to fix it. An *optimized* app is fine — asking
users to switch to *Unrestricted* is optional advice for aggressive OEM battery savers
(see [OEM notes](#oem-notes)), not a requirement. Battery settings never count towards
`allRequiredGranted`.

`openBatteryOptimizationSettings()` tries, in order, only screens that resolve on the device:

1. The app's own battery page — `android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL` with
   `package:<your app>` data, served by AOSP Settings (`AdvancedPowerUsageDetailActivity`)
   and showing *Unrestricted / Optimized / Restricted* or *Allow background usage*. It is a
   hidden Settings action, so some OEM builds lack it.
2. The app's details page (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`), whose *Battery*
   entry leads to the same choice.
3. The battery-optimisation list (`Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`).

> **0.2.0 changed `batteryOptimization`.** In 0.1.x it was `granted` only when the app was
> exempt from battery optimisation (*Unrestricted*), so the Android default (*Optimized*,
> *Allow background usage* on) was reported as `denied` — a false alarm. It is now `denied`
> only when the app is background-restricted. If you need the old meaning, use
> `batteryUsage === 'unrestricted'`.

Reading permissions never creates notification channels, so call `configure()` first (a
channel's sound and vibration are frozen when it is created; one created with default
settings before your `configure()` would linger in the system settings forever).
`openNotificationSettings()` before `configure()` opens the app's notification settings.

### `configure(config)`

<p align="center">
  <img src="https://raw.githubusercontent.com/getsettalk/react-native-call-reminder-/main/docs/images/customize.png" alt="The same call screen with default settings, with a teal brand and Hindi labels, and in tap mode with custom labels" width="860">
</p>

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
| `labels` | English | `answer`, `decline`, `incomingTitle`, `speaking`, `listening`, `ended`, `endCall`, `replay`, `tapToAnswer`, `swipeToAnswer` ("Swipe up to answer"), `swipeToDecline` ("Swipe up to decline"). |
| `ringingStyle` | `'call'` | Android ringing notification template. `'call'`: Android's call-style notification (Answer/Decline pills, top of the shade) — from Android 14 the only ongoing notification users **can't swipe away**, so a ringing reminder can't be lost by an accidental swipe or "Clear all"; falls back to `'standard'` automatically if a device rejects it. `'standard'`: plain notification with two actions (ongoing, but swipeable on Android 14+). No effect on iOS, where notifications can always be dismissed. |
| `answerGesture` | `'swipe'` | How the ringing call screen is answered/declined: `'swipe'` (drag the button up) or `'tap'` (the 0.1.x behaviour). See [Answering on the call screen](#answering-on-the-call-screen). |
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

### Answering on the call screen

With the default `answerGesture: 'swipe'`, the ringing call screen (Android full-screen
call screen, iOS in-app call screen) is answered by **dragging the green Answer button
upwards** and declined by dragging the red Decline button upwards:

- Each button follows the finger with increasing resistance; small chevrons above it and a
  hint below it (`labels.swipeToAnswer` / `labels.swipeToDecline`) say what to do.
- Released past ~40 % of the drag distance (140 dp/pt, shorter on small or landscape
  screens), or flung upwards quickly, it commits with a haptic tick; otherwise it springs
  back.
- A plain tap does **not** answer — so a phone in a pocket or bag cannot pick up the call —
  it bounces the button and briefly emphasises the hint.
- Only one button can be dragged at a time and only the first finger counts.
- Screen readers: TalkBack / VoiceOver users double-tap the button as usual (it answers or
  declines straight away), and the custom accessibility actions *Answer* / *Decline* are
  offered. Switch Access / Switch Control and hardware keyboards activate it directly too.
- The Answer button keeps its "breathing" pulse while idle (paused while a button is
  touched). Both circles stay level and are never clipped.

Only the ringing state uses the gesture: the buttons after answering (*Repeat*, *End call*,
your actions) are ordinary taps, and the notification's Answer / Decline actions are system
buttons and unchanged. Use `answerGesture: 'tap'` to keep tap-to-answer.

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

## Diagnostics

"My reminder call never rang" is almost always the phone, not your server: an OEM battery
manager that force-stops the app when it is swiped out of Recents (realme, OPPO, OnePlus,
Xiaomi, vivo…), a blocked channel, Do Not Disturb, a missing permission. `getDiagnostics()`
collects everything the library can read about this in one JSON-serialisable object, so
support can look at a user's phone **remotely**:

<p align="center">
  <img src="https://raw.githubusercontent.com/getsettalk/react-native-call-reminder-/main/docs/images/diagnostics.png" alt="getDiagnostics output for a realme phone that force-stops the app, turned into findings: critical force-stop with the auto-launch fix, battery restricted warning, rarely-used warning, and OK checks" width="860">
</p>

```ts
import CallReminder from 'react-native-call-reminder';

// e.g. behind a "Send diagnostics" button on your Help screen
export async function sendCallDiagnostics(): Promise<void> {
  const diagnostics = await CallReminder.getDiagnostics();
  await fetch('https://api.example.com/v1/support/call-diagnostics', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${await getToken()}` },
    body: JSON.stringify({ appVersion: APP_VERSION, diagnostics }),
  });
}
```

You can also collect it from your FCM background handler when your server sends a
"debug" data message to one consenting user — and if that message never answers, the
app is most likely force-stopped right now (see `forceStoppedRecently`).

A realme phone after an OEM "swipe kill" (abridged):

```json
{
  "platform": "android", "osVersion": "14", "sdkInt": 34,
  "manufacturer": "realme", "brand": "realme", "model": "RMX3686", "device": "RE58B2L1",
  "rom": { "name": "realme UI", "version": "V5.0", "display": "RMX3686_14.0.0.600(EX01)" },
  "permissions": { "notifications": "granted", "channel": "granted", "fullScreenIntent": "granted",
                   "batteryUsage": "optimized", "oemHasAutoStartManager": true, "…": "…" },
  "standbyBucket": "active", "powerSaveMode": false, "deviceIdle": false,
  "interruptionFilter": "all", "dndAllowsAlarms": true,
  "notificationChannels": [
    { "id": "call_reminder_incoming", "name": "Reminder calls", "importance": "high", "blocked": false,
      "sound": true, "vibration": true, "bypassDnd": false, "lockscreenVisibility": "public" }
  ],
  "appNotificationsEnabled": true,
  "exitReasons": [
    { "timestamp": 1767100000000, "reason": "user_requested", "status": 0, "importance": "cached",
      "description": "stop com.example.app due to from pid 2786", "processName": "com.example.app" }
  ],
  "forceStoppedRecently": true, "keyguardSecure": true,
  "tts": { "engine": "com.google.android.tts", "defaultLanguage": "hi-IN" },
  "timezone": "Asia/Kolkata", "locale": "en-IN", "ios": null, "collectedAt": 1767200000000
}
```

Every field is best-effort: whatever cannot be read on a device is `unknown`, `null`,
`false` or `[]` — the promise does not reject because of it. Android collects on a
background thread (a handful of quick system-service reads), needs no
permission, never creates notification channels and never starts the TTS engine.

| Field | What it is | When it is bad → what to tell the user |
| --- | --- | --- |
| `platform`, `osVersion`, `sdkInt`, `manufacturer`, `brand`, `model`, `device` | Device identity. `manufacturer` / `brand` lower-cased (`realme`, `xiaomi`, `redmi`…); iOS: `apple`, model identifier (`iPhone15,2`), `sdkInt` null. | — Pick the right OEM instructions from it. |
| `rom` | Android OEM skin from system properties: `name` (`realme UI`, `ColorOS`, `OxygenOS`, `HyperOS`, `MIUI`, `OriginOS`, `Funtouch OS`, `MagicOS`, `Magic UI`, `HarmonyOS`, `EMUI`, `One UI`, `Flyme`, or null for stock-like Android), `version` as reported (`V5.0`, `OS2.0`, `6.1`), `display` = `Build.DISPLAY`. iOS: `display` is the OS build string. | ColorOS family, HyperOS/MIUI, OriginOS/Funtouch, EMUI/MagicOS: aggressive background killing — see [OEM notes](#oem-notes). |
| `permissions` | Exactly what `getPermissions()` returns. | See [Permissions](#permissions): `notifications` → `requestNotificationPermission()`; `channel` → `openNotificationSettings()`; `fullScreenIntent` → `requestFullScreenIntentPermission()`; `batteryUsage: 'restricted'` → `openBatteryOptimizationSettings()`. |
| `forceStoppedRecently` | Android: the app was force-stopped before the current process started — while stopped it gets **no** pushes, so nothing can ring. Android 15+: exact (`ApplicationStartInfo.wasForceStopped()`; the first launch after installing does not count). Android 11–14: inferred from the newest main-process death being `user_requested` / `user_stopped`. | `true` on realme / OPPO / OnePlus / Xiaomi / vivo / Huawei / Honor: *"Turn on **Auto launch / Autostart** for the app (`openAutoStartSettings()`), set its battery use to *Allow background activity / No restrictions*, and **lock the app in Recents** (open Recents, long-press or pull down on the app's card → Lock) so clearing Recents doesn't stop it."* Caveats: a deliberate Settings → *Force stop* looks the same, and on stock Android 11–14 (Pixel, Motorola…) swiping the app out of Recents also records `user_requested` without stopping it (harmless there). |
| `exitReasons` | Android 11+: the app's last ≤ 10 process deaths, newest first: `timestamp`, `reason`, `status`, `importance` (state when it died), `description`, `processName`. | Repeated `user_requested` → as above. `low_memory` → the phone ran out of RAM: lock the app in Recents, close heavy apps. `crash` / `crash_native` / `anr` → your bug: check crash reports at that time. `excessive_resource_usage` / `freezer` → the system punished background work. `permission_change` → the user revoked a permission. |
| `standbyBucket` | Android 9+ app standby bucket. Almost always `active` while the app is open — most telling when collected in the background. | `rare` / `restricted`: background work is deferred and high-priority pushes may be rationed. Ask the user to open the app now and then, and to set battery use to *Unrestricted* on aggressive OEMs. |
| `powerSaveMode` | Android Battery Saver / iOS Low Power Mode is on. | OEM battery savers often hold back pushes: turn it off, or exempt the app. |
| `deviceIdle` | Android: the device is in Doze right now (false while in use). | Informational: high-priority FCM still wakes the app in Doze. |
| `interruptionFilter` | Android Do Not Disturb: `all` (off), `priority`, `alarms`, `none` (total silence). iOS: `not_applicable`. | `none` → *"Turn off Do Not Disturb, or use Priority only with alarms allowed."* `alarms` → calls ring (default `ringAudioUsage: 'alarm'`) but your ordinary reminder channels stay silent. |
| `dndAllowsAlarms` | Android: whether DND lets **alarms** through — the policy in force while DND is on in priority mode, otherwise the user's DND settings (what happens once DND turns on). null if unreadable / iOS. | `false` (with `priority`) → *"In Do Not Disturb settings, allow Alarms"* — calls ring with alarm audio by default ([Do Not Disturb](#do-not-disturb)). |
| `notificationChannels` | Android 8+: **all** of the app's channels (also your own reminder channels): `importance`, `blocked`, `sound`, `vibration`, `bypassDnd`, `lockscreenVisibility` (`no_override` = each notification decides). | `blocked` → *"Turn this notification category on"* (`openNotificationSettings(id)`). The call channel below `high` → it will not pop up or ring: set it back to *Urgent / Pop on screen*. A reminder channel at `low` / `min` or `sound: false` → silent. `secret` → hidden on the lock screen. |
| `appNotificationsEnabled` | The app-wide notifications switch (iOS: authorized). | `false` → *"Allow notifications for the app."* |
| `keyguardSecure` | A PIN / pattern / password (iOS: passcode) is set. | Informational: with `lockScreenPrivacy: 'private'` details show only after unlocking. |
| `tts` | `engine`: the default TTS engine package (null = none installed; iOS `AVSpeechSynthesizer`); `defaultLanguage` where known. | `engine: null` → the call rings but cannot speak: install *Speech Services by Google*. Check the reminder language with `isLanguageAvailable()`. |
| `timezone`, `locale` | IANA zone id (`Asia/Kolkata`) and BCP-47 locale. | Zone differs from the user's zone on your server → reminders ring at the wrong local time. |
| `ios.lowPowerMode` | iOS Low Power Mode. | Informational: alert pushes still arrive. |
| `ios.backgroundRefresh` | Background App Refresh: `available`, `denied`, `restricted`. | Does not block alert pushes; blocks background work for data-only pushes: *Settings → General → Background App Refresh.* |
| `ios.notificationSettings` | Raw `UNNotificationSettings`: `authorizationStatus` (`authorized`, `provisional`, `denied`…) and `alert` / `sound` / `lockScreen` / `notificationCenter` / `timeSensitive` / `criticalAlert` / `scheduledDelivery` settings (`enabled`, `disabled`, `not_supported`). | `denied` → allow notifications. `alertSetting` / `soundSetting` `disabled` → turn on banners / sounds. `timeSensitiveSetting: 'disabled'` → turn on *Time Sensitive Notifications* (Focus can hold the call back otherwise). `scheduledDeliverySetting: 'enabled'` → the app is in the *Scheduled Summary*: switch it to *Immediate Delivery*. |
| `collectedAt` | Device clock (epoch ms) when collected. | Far from your server's time → wrong device clock. |

Privacy: the snapshot contains no personal identifiers (no IMEI, serial number,
advertising ID, phone number or accounts), but device model, OS build, time zone, locale and
channel names are device information. Send it only for support and with the user's
knowledge, never use it to fingerprint or track, and declare it if you collect it (Google
Play *Data safety*: App info and performance → Diagnostics / Crash logs; App Store privacy
details: Diagnostics → Other Diagnostic Data).

## Android

### What the library adds to your manifest

`POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK` (held by React
Native's `HeadlessJsTaskService` only while a background event task runs), the call
activity (own task, excluded from recents, shown over the lock screen), a non-exported
receiver and the headless service. `<queries>` entries make the TTS engine, OEM
auto-start managers and the battery-usage settings pages visible on Android 11+. Nothing is
exported.

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

On stock Android the battery setting only matters when it is *Restricted*
(`batteryUsage === 'restricted'`, see [Battery usage](#battery-usage)).

Whether a user's phone actually kills the app this way shows up in
[`getDiagnostics()`](#diagnostics): `forceStoppedRecently`, `exitReasons` and `rom` (e.g.
`realme UI`, `HyperOS`).

`getPermissions().oemHasAutoStartManager` tells you whether such a screen exists.

### Overriding resources

Every resource is prefixed `callreminder_` and can be redefined in your app (including
translations in `values-xx/`): strings `callreminder_label_*` (including
`callreminder_label_swipe_to_answer` / `callreminder_label_swipe_to_decline`),
`callreminder_channel_*`; colours `callreminder_background`, `callreminder_accent`,
`callreminder_text`, `callreminder_answer`, `callreminder_decline`; drawables
`callreminder_ic_notification`, `callreminder_ic_call`, `callreminder_ic_call_end`,
`callreminder_ic_replay`, `callreminder_ic_swipe_up`; theme
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
