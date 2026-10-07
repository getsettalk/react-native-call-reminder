# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres to
[Semantic Versioning](https://semver.org/).

## [0.3.0] - 2026-10-07

### Added

- `configure({ ringingStyle: 'call' | 'standard' })` (default `'call'`): the Android ringing
  notification now uses the call-style template (Answer/Decline pills, ranked at the top of
  the shade). From Android 14 that is the only kind of ongoing notification users can't swipe
  away, so a ringing reminder can no longer be lost by an accidental swipe; it is also flagged
  `FLAG_NO_CLEAR` so "Clear all" never removes it. Devices that reject a call-style
  notification fall back to the standard template automatically.

- `getDiagnostics(): Promise<CallReminderDiagnostics>`: one read-only snapshot of everything
  that can keep a reminder call from reaching the device, for debugging users' phones
  remotely (send it to your backend). Fields: `platform`, `osVersion`, `sdkInt`,
  `manufacturer`, `brand`, `model`, `device`, `rom` (OEM skin), `permissions` (the
  `getPermissions()` object), `standbyBucket`, `powerSaveMode`, `deviceIdle`,
  `interruptionFilter`, `dndAllowsAlarms`, `notificationChannels` (all of the app's
  channels), `appNotificationsEnabled`, `exitReasons`, `forceStoppedRecently`,
  `keyguardSecure`, `tts`, `timezone`, `locale`, `ios` and `collectedAt`. Every field is
  best-effort (`unknown` / `null` / `false` / `[]` when it cannot be read); the promise only
  rejects (`diagnostics_failed`) on a truly unexpected error. README → *Diagnostics* explains
  every field and what to tell the user when it is bad.
- Exported types: `CallReminderDiagnostics`, `RomInfo`, `StandbyBucket`, `InterruptionFilter`,
  `NotificationChannelInfo`, `ChannelImportance`, `LockscreenVisibility`, `ProcessExitInfo`,
  `ProcessExitReason`, `ProcessImportance`, `TtsInfo`, `IosDiagnostics`,
  `IosNotificationSettings`, `IosAuthorizationStatus`, `IosNotificationSetting`,
  `IosBackgroundRefresh`.
- Android: collected on a background thread. OEM skin detection (realme UI, ColorOS,
  OxygenOS, HyperOS, MIUI, OriginOS, Funtouch OS, MagicOS / Magic UI, HarmonyOS, EMUI,
  One UI, Flyme) reads system properties through `android.os.SystemProperties` by
  reflection, guarded, falling back to `Build.DISPLAY`. Process deaths come from
  `ActivityManager.getHistoricalProcessExitReasons()` (Android 11+, reasons mapped by value
  up to `REASON_PACKAGE_UPDATED`); `forceStoppedRecently` is exact on Android 15+
  (`ApplicationStartInfo.wasForceStopped()`, ignoring the first launch after installation)
  and inferred from the newest main-process exit
  (`user_requested` / `user_stopped`) on Android 11–14. The TTS engine is read from the
  system setting / installed engines without starting it. No new permissions or manifest
  entries; notification channels are never created.
- iOS: notification settings, Low Power Mode, Background App Refresh, model identifier and
  passcode state. The pod now links `LocalAuthentication` (only `canEvaluatePolicy`, which
  never prompts and needs no Face ID usage description). No new required-reason APIs; the
  privacy manifest is unchanged.
- Jest mock: `getDiagnostics` resolves a healthy Android snapshot.

### Documentation

- README screenshots: illustrative mockups of the Android call screen (ringing, speaking,
  heads-up fallback), iOS (Time Sensitive notification, in-app call screen), customisation,
  the call flow and diagnostics. Sources in `docs/mockups/`, rendered with
  `scripts/render-mockups.sh`; not part of the npm package.


## [0.2.0] - 2026-10-01

### Added

- `getPermissions().batteryUsage`: `'unrestricted' | 'optimized' | 'restricted' |
  'not_applicable' | 'unknown'`. Android: `restricted` when background-restricted
  (`ActivityManager.isBackgroundRestricted()`, Android 9+; on Android 14/15 "Allow background
  usage" off), else `unrestricted` when exempt from battery optimisation, else `optimized`
  (the default). iOS: `not_applicable`.
- `getPermissions().backgroundRestricted: boolean` (Android 9+; `false` elsewhere and on iOS).
- Swipe to answer / decline on the ringing call screen (Android full-screen call screen and
  iOS in-app call screen): drag the green Answer button up to answer, the red Decline button
  up to decline. The button follows the finger 1:1 up to the commit point (~40 % of the
  drag distance), then with resistance; past that point it grows slightly with a haptic
  tick, and releasing there (or a fast upward fling) commits; otherwise it springs back.
  Floating chevrons and a hint label invite the gesture; a plain tap only bounces the button and
  emphasises the hint, so pocket touches cannot answer. One button and one finger at a time.
  TalkBack / VoiceOver (double-tap), Switch Access / Switch Control and keyboards still
  activate the buttons directly, and custom accessibility actions "Answer" / "Decline" are
  exposed. The buttons stay clear of the gesture-navigation area.
- `configure({ answerGesture: 'swipe' | 'tap' })` (default `'swipe'`); `'tap'` keeps the
  0.1.x tap-to-answer behaviour. The in-call buttons after answering and the notification's
  Answer / Decline actions are unchanged.
- Labels `swipeToAnswer` ("Swipe up to answer") and `swipeToDecline` ("Swipe up to decline"),
  also as Android string resources `callreminder_label_swipe_to_answer` /
  `callreminder_label_swipe_to_decline`; drawable `callreminder_ic_swipe_up`.

### Changed

- **`getPermissions().batteryOptimization` no longer reports the Android default as a
  problem.** It is now `denied` only when the app is background-restricted and `granted`
  otherwise — including when it is battery-*optimized* ("Allow background usage" on), which
  does not delay high-priority FCM messages. In 0.1.0 it was `granted` only when the app was
  exempt from battery optimisation ("Unrestricted"), so most devices showed `denied`. Code
  that needs the old meaning should check `batteryUsage === 'unrestricted'`.
  `allRequiredGranted` is unchanged (battery settings never counted).
- `openBatteryOptimizationSettings()` opens the most specific page for the app's battery
  usage: the app's own battery page (`android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL`
  with `package:` data) where it resolves, else the app's details page, else the
  battery-optimisation list. It resolves with the re-checked `batteryOptimization` (new
  semantics) on return, and immediately — without opening anything — only when the app is
  already `unrestricted` (an optimized app can still be switched to Unrestricted). Still no
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- The library manifest adds `<queries>` entries for those three settings actions so they
  can be resolved on Android 11+.
- iOS: the Answer button's idle animation is now the same in-place "breathing" scale as on
  Android (it used to hop up and down, leaving the two circles uneven).

## [0.1.0] - 2026-10-01

### Added

- TurboModule (New Architecture) with codegen spec `RNCallReminderSpec`.
- Android: dedicated HIGH-importance call channel, insistent `CATEGORY_ALARM` notification,
  full-screen call activity (lock screen, edge-to-edge, predictive back), heads-up fallback
  when full-screen intents are denied, quiet notification while the user is on a phone call,
  timeout backed by `AlarmManager`, text-to-speech with language fallback and audio focus,
  durable event queue with a headless JS task.
- Android permission helpers: notifications, full-screen intent, exact alarms, battery
  optimisation and OEM auto-start managers, all resolving when the user returns from Settings.
- iOS: Time Sensitive / Critical notifications with Answer/Decline category, in-app call
  screen, `AVSpeechSynthesizer` speech, durable event queue, and `CallReminderNotifications`
  for routing responses without replacing the notification-center delegate.
- JS: validated public API, live listeners with acknowledgement, background handler,
  `getPendingEvents` / `acknowledgeEvents`.
- `fallbackSpeakText` (and the `fallback_speak_text` remote key on iOS): wording spoken when
  the call's language has no voice on the device.
- `lockScreenPrivacy: 'private'` also covers the Android full-screen call screen (details
  hidden and unlock required to answer while a secure lock screen is up).
- iOS: `ios.remoteActions`, `ios.remoteEventPayloadKeys`, `ios.requiredRemotePayload`; in-app
  ring while the app is open; privacy manifest for the `UserDefaults` required-reason API.
- `react-native-call-reminder/jest/mock` for consumers' Jest tests.
