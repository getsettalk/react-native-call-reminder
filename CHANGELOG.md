# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres to
[Semantic Versioning](https://semver.org/).

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
