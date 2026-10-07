/**
 * Public types for react-native-call-reminder.
 *
 * Everything here is platform-neutral; fields that only apply to one platform
 * say so and are ignored on the other.
 */

/**
 * - `granted` / `denied`: the OS reports it explicitly.
 * - `not_determined`: the user has not been asked yet (a request will prompt).
 * - `not_applicable`: the concept does not exist on this OS / OS version.
 * - `unknown`: exists but cannot be queried (e.g. OEM auto-start managers).
 */
export type PermissionState =
  | 'granted'
  | 'denied'
  | 'not_determined'
  | 'not_applicable'
  | 'unknown';

/** See `CallReminderPermissions.batteryUsage`. */
export type BatteryUsage = 'unrestricted' | 'optimized' | 'restricted' | 'not_applicable' | 'unknown';

export interface CallReminderPermissions {
  platform: 'android' | 'ios';
  osVersion: string;
  /** Android API level. */
  sdkInt?: number;
  /** Android `Build.MANUFACTURER` (lower-cased); `'apple'` on iOS. */
  manufacturer?: string;
  /** Android POST_NOTIFICATIONS / app notifications switch; iOS authorization. */
  notifications: PermissionState;
  /**
   * Android: the call channel exists, is enabled and is HIGH importance
   * (`not_determined` until `configure()` or `showIncomingCall()` created it —
   * reading permissions never creates channels). iOS: not_applicable.
   */
  channel: PermissionState;
  /** Android 14+: `canUseFullScreenIntent()`; older Android: granted; iOS: not_applicable. */
  fullScreenIntent: PermissionState;
  /**
   * Android 12+: `canScheduleExactAlarms()`; older Android: granted.
   * `not_applicable` when the host app declares neither SCHEDULE_EXACT_ALARM
   * nor USE_EXACT_ALARM (the library declares neither), and on iOS.
   */
  exactAlarm: PermissionState;
  /**
   * Android: whether battery management can hold back reminder calls.
   * `granted` unless the app is background-restricted (`batteryUsage ===
   * 'restricted'`), i.e. also when it is battery-*optimized* (the Android
   * default, "Allow background usage" on), which does not delay high-priority
   * FCM messages. `denied` only when background-restricted. iOS: not_applicable.
   *
   * Before 0.2.0 this was `granted` only when the app was exempt from
   * battery optimisation ("Unrestricted"); read `batteryUsage` for that.
   */
  batteryOptimization: PermissionState;
  /**
   * Android: the app's battery usage setting.
   * - `unrestricted`: exempt from battery optimisation (`isIgnoringBatteryOptimizations`).
   * - `optimized`: the Android default; fine for high-priority FCM.
   * - `restricted`: background-restricted (`ActivityManager.isBackgroundRestricted()`,
   *   Android 9+; on Android 14/15 "Allow background usage" off / "Restricted"):
   *   calls may arrive late or not at all.
   * - `unknown`: could not be read.
   * iOS: `not_applicable`.
   */
  batteryUsage: BatteryUsage;
  /** Android 9+: `ActivityManager.isBackgroundRestricted()`. False elsewhere and on iOS. */
  backgroundRestricted: boolean;
  /** OEM auto-start cannot be queried: `unknown` when the OEM ships a manager, else not_applicable. */
  autoStart: PermissionState;
  oemHasAutoStartManager: boolean;
  /** iOS 15+: Time Sensitive notifications setting; otherwise not_applicable. */
  timeSensitive: PermissionState;
  /** iOS: Critical Alerts setting (needs the Apple-granted entitlement); otherwise not_applicable. */
  criticalAlerts: PermissionState;
  /** Whether notifications are shown on the lock screen. */
  lockScreen: PermissionState;
  /** Android: notifications + channel + fullScreenIntent. iOS: notifications. */
  allRequiredGranted: boolean;
}

// ---------------------------------------------------------------------------
// Diagnostics (`getDiagnostics()`)
// ---------------------------------------------------------------------------

/**
 * Android 9+ app standby bucket of this app (`UsageStatsManager.getAppStandbyBucket()`):
 * how often the system lets it run jobs and alarms in the background. While the app is
 * open it is almost always `active`; the value is most telling when collected in the
 * background (e.g. from a push handler).
 * - `exempted`: exempt from standby (system / allow-listed apps).
 * - `restricted` (Android 11+): the strictest bucket; background work is heavily limited.
 * - `never`: the app has never been used since installation.
 * - `unknown`: could not be read or a value Android did not document.
 * - `not_applicable`: below Android 9 and on iOS.
 */
export type StandbyBucket =
  | 'exempted'
  | 'active'
  | 'working_set'
  | 'frequent'
  | 'rare'
  | 'restricted'
  | 'never'
  | 'unknown'
  | 'not_applicable';

/**
 * Android Do Not Disturb state (`NotificationManager.getCurrentInterruptionFilter()`):
 * `all` = DND off, `priority` = priority only, `alarms` = alarms only, `none` = total
 * silence. `not_applicable` on iOS (Focus cannot be read without an entitlement).
 */
export type InterruptionFilter =
  | 'all'
  | 'priority'
  | 'alarms'
  | 'none'
  | 'unknown'
  | 'not_applicable';

/** Android notification channel importance (`NotificationManager.IMPORTANCE_*`). */
export type ChannelImportance =
  | 'none'
  | 'min'
  | 'low'
  | 'default'
  | 'high'
  | 'max'
  | 'unspecified';

/**
 * Android channel lock-screen visibility (`Notification.VISIBILITY_*`). `no_override`:
 * the channel does not override it, so each notification's own visibility applies
 * (subject to the device's lock-screen setting).
 */
export type LockscreenVisibility = 'public' | 'private' | 'secret' | 'no_override' | 'unknown';

/** One of the app's Android notification channels — every channel, not only the call channel. */
export interface NotificationChannelInfo {
  id: string;
  /** User-visible channel name. */
  name: string;
  importance: ChannelImportance;
  /** Importance `none` (the user switched the channel off) or its channel group is blocked. */
  blocked: boolean;
  /** The channel has a sound. */
  sound: boolean;
  /** The channel vibrates. */
  vibration: boolean;
  /** The channel may override Do Not Disturb (only the user can grant this). */
  bypassDnd: boolean;
  lockscreenVisibility: LockscreenVisibility;
}

/**
 * Why a process of this app died (`ApplicationExitInfo.REASON_*`, Android 11+).
 * `user_requested` covers Settings → *Force stop*, OEM "swipe kills" and, on stock
 * Android, swiping the app out of Recents; `unknown` is `REASON_UNKNOWN` or a value newer
 * than this library.
 */
export type ProcessExitReason =
  | 'user_requested'
  | 'user_stopped'
  | 'crash'
  | 'crash_native'
  | 'anr'
  | 'low_memory'
  | 'signaled'
  | 'excessive_resource_usage'
  | 'dependency_died'
  | 'permission_change'
  | 'initialization_failure'
  | 'freezer'
  | 'package_state_change'
  | 'package_updated'
  | 'exit_self'
  | 'other'
  | 'unknown';

/**
 * Process importance when it died (`RunningAppProcessInfo.IMPORTANCE_*`). `foreground`
 * includes foreground services; `other` is anything else (e.g. top-sleeping).
 */
export type ProcessImportance =
  | 'foreground'
  | 'visible'
  | 'perceptible'
  | 'service'
  | 'cached'
  | 'gone'
  | 'other';

/** A recorded death of one of the app's processes (Android 11+ `ApplicationExitInfo`). */
export interface ProcessExitInfo {
  /** Epoch milliseconds (device clock — compare with `collectedAt`). */
  timestamp: number;
  reason: ProcessExitReason;
  /** Exit status (`exit_self`) or signal number (`signaled`, `crash_native`, …). */
  status: number;
  importance: ProcessImportance;
  /** Free-text detail from the system, e.g. `"remove task"`, `"stop com.example due to …"`. */
  description: string | null;
  /** The app's main process has the package name; others have a `:suffix`. */
  processName: string | null;
}

/**
 * Best-effort detection of the manufacturer's Android skin from system properties.
 * `name` is one of `realme UI`, `ColorOS`, `OxygenOS`, `HyperOS`, `MIUI`, `OriginOS`,
 * `Funtouch OS`, `MagicOS`, `Magic UI`, `HarmonyOS`, `EMUI`, `One UI`, `Flyme`, or
 * null when none was recognised (stock Android, Pixel, Motorola, Nokia, …).
 */
export interface RomInfo {
  name: string | null;
  /** The skin's own version as the device reports it, e.g. `V5.0`, `OS2.0`, `6.1`. */
  version: string | null;
  /** Android: `Build.DISPLAY` (the build id). iOS: the OS version and build string. */
  display: string | null;
}

export interface TtsInfo {
  /**
   * Android: package of the default text-to-speech engine (e.g. `com.google.android.tts`),
   * null when no engine is installed. iOS: `AVSpeechSynthesizer`.
   */
  engine: string | null;
  /**
   * The engine's default language (BCP-47). Android: known once the library's speech
   * engine has been used in this process, or from the system's TTS setting where
   * readable; null otherwise (the engine then normally follows the device language).
   * iOS: `AVSpeechSynthesisVoice.currentLanguageCode()`.
   */
  defaultLanguage: string | null;
}

export type IosAuthorizationStatus =
  | 'not_determined'
  | 'denied'
  | 'authorized'
  | 'provisional'
  | 'ephemeral'
  | 'unknown';

/** `UNNotificationSetting`. */
export type IosNotificationSetting = 'enabled' | 'disabled' | 'not_supported' | 'unknown';

/** `UIApplication.backgroundRefreshStatus`. */
export type IosBackgroundRefresh = 'available' | 'denied' | 'restricted' | 'unknown';

/** The raw `UNNotificationSettings` of the app. */
export interface IosNotificationSettings {
  authorizationStatus: IosAuthorizationStatus;
  alertSetting: IosNotificationSetting;
  soundSetting: IosNotificationSetting;
  lockScreenSetting: IosNotificationSetting;
  notificationCenterSetting: IosNotificationSetting;
  timeSensitiveSetting: IosNotificationSetting;
  criticalAlertSetting: IosNotificationSetting;
  /** `enabled` when the user moved the app's notifications into the Scheduled Summary. */
  scheduledDeliverySetting: IosNotificationSetting;
}

export interface IosDiagnostics {
  /** Low Power Mode is on. */
  lowPowerMode: boolean;
  /** Background App Refresh for this app. */
  backgroundRefresh: IosBackgroundRefresh;
  notificationSettings: IosNotificationSettings;
}

/**
 * Everything the library can read about why a reminder call might not reach this device.
 * Every field is best-effort: a value that cannot be read is `unknown` / `null` / `false`
 * / `[]` instead of failing the call. JSON-serialisable (no `undefined`), so it can be
 * sent to a backend as is. See README → Diagnostics.
 */
export interface CallReminderDiagnostics {
  platform: 'android' | 'ios';
  /** Android `Build.VERSION.RELEASE`; iOS `systemVersion`. */
  osVersion: string;
  /** Android API level; null on iOS. */
  sdkInt: number | null;
  /** Android `Build.MANUFACTURER`, lower-cased (e.g. `realme`, `xiaomi`); `apple` on iOS. */
  manufacturer: string;
  /** Android `Build.BRAND`, lower-cased (e.g. `realme`, `redmi`, `poco`); `apple` on iOS. */
  brand: string;
  /** Android `Build.MODEL` (e.g. `RMX3686`); iOS model identifier (e.g. `iPhone15,2`). */
  model: string;
  /** Android `Build.DEVICE` (code name); iOS `UIDevice.model` (`iPhone`, `iPad`). */
  device: string;
  rom: RomInfo;
  /** The same object `getPermissions()` resolves with. */
  permissions: CallReminderPermissions;
  standbyBucket: StandbyBucket;
  /** Android Battery Saver; iOS Low Power Mode. */
  powerSaveMode: boolean;
  /** Android: the device is in Doze right now (false while the app is in use). iOS: false. */
  deviceIdle: boolean;
  interruptionFilter: InterruptionFilter;
  /**
   * Android: whether Do Not Disturb lets alarms through (`PRIORITY_CATEGORY_ALARMS`) —
   * the policy in force while DND is on in priority mode (Android 11+: of all active
   * modes), otherwise the user's DND settings, i.e. what happens once DND turns on.
   * Android 7–8: always true (priority mode always allowed alarms). Android ignores it
   * for `interruptionFilter` `none` (alarms silenced) and `alarms` (alarms allowed).
   * null when it could not be read, and on iOS.
   */
  dndAllowsAlarms: boolean | null;
  /** Android 8+: all of the app's channels. Empty below Android 8 and on iOS. */
  notificationChannels: NotificationChannelInfo[];
  /** The app-wide notifications switch (Android) / authorization (iOS). */
  appNotificationsEnabled: boolean;
  /** Android 11+: the app's latest process deaths, newest first, at most 10. Empty elsewhere. */
  exitReasons: ProcessExitInfo[];
  /**
   * Android: the app was force-stopped before the current process started — while it
   * was, pushes (FCM) were not delivered and nothing could ring. Android 15+: exact
   * (`ApplicationStartInfo.wasForceStopped()`; the first launch after installing, when an
   * app is also "stopped", does not count). Android 11–14: inferred — the newest exit
   * of the main process is `user_requested` / `user_stopped`, which is also what a
   * stock-Android swipe out of Recents looks like (harmless there). Either way a
   * deliberate Settings → *Force stop* looks the same as an OEM swipe kill. False below
   * Android 11 and on iOS.
   */
  forceStoppedRecently: boolean;
  /** A PIN, pattern, password (Android) or passcode (iOS) is set. */
  keyguardSecure: boolean;
  tts: TtsInfo;
  /** IANA time zone id, e.g. `Asia/Kolkata`. */
  timezone: string;
  /** Device locale as a BCP-47 tag, e.g. `en-IN`. */
  locale: string;
  /** iOS-only details; null on Android. */
  ios: IosDiagnostics | null;
  /** Epoch milliseconds when this snapshot was taken (device clock). */
  collectedAt: number;
}

export type CallActionStyle = 'primary' | 'secondary' | 'destructive';

/** A button offered on the call screen after the call has been answered. */
export interface CallAction {
  id: string;
  label: string;
  style?: CallActionStyle;
  /** End the call when pressed (default true). */
  dismissesCall?: boolean;
  /** Bring the host app to the foreground after the action (default false). */
  opensApp?: boolean;
}

export interface CallReminderLabels {
  answer: string;
  decline: string;
  /** Small heading above the caller name while ringing, e.g. "Incoming reminder call". */
  incomingTitle: string;
  /** Status line while the reminder is being spoken. */
  speaking: string;
  /** Status line once speech has finished and actions are awaited. */
  listening: string;
  /** Status line shown briefly after the call ends. */
  ended: string;
  endCall: string;
  replay: string;
  /** Shown on the quiet notification posted while the user is busy. */
  tapToAnswer: string;
  /** Hint under the Answer button while ringing with `answerGesture: 'swipe'`. */
  swipeToAnswer: string;
  /** Hint under the Decline button while ringing with `answerGesture: 'swipe'`. */
  swipeToDecline: string;
}

/**
 * How the ringing call screen is answered or declined:
 * - `swipe` (default): drag the Answer (or Decline) button upwards. A plain
 *   tap only nudges the button, so a phone in a pocket does not answer.
 *   TalkBack/VoiceOver, Switch Access and keyboard users activate the buttons
 *   normally (double-tap / custom actions "Answer" and "Decline").
 * - `tap`: a tap on the button answers/declines (the 0.1.x behaviour).
 * The notification's Answer/Decline actions are system buttons and unaffected.
 */
export type AnswerGesture = 'swipe' | 'tap';

/**
 * Android template of the ringing notification.
 * - `call` (default): Android's call-style template — Answer/Decline pills, ranked
 *   at the top of the shade and, from Android 14, the only kind of ongoing
 *   notification the user can't swipe away (none can be removed with "Clear
 *   all"). Falls back to `standard` automatically if a device rejects it.
 * - `standard`: a plain notification with Answer/Decline actions. Ongoing too, but
 *   Android 14+ lets the user swipe it away (reported as `declined` / `dismissed`).
 * iOS notifications can always be dismissed; this has no effect there.
 */
export type RingingStyle = 'call' | 'standard';

export interface CallReminderIosConfig {
  /** Notification category registered for call reminders (default `CALL_REMINDER`). */
  categoryId?: string;
  /** Thread identifier grouping call notifications (default `call-reminder`). */
  threadId?: string;
  answerTitle?: string;
  declineTitle?: string;
  /** Bundled sound file name (e.g. `call_reminder.caf`), `'default'` or `'silent'`. */
  sound?: string;
  /** Default `timeSensitive`. `critical` is only used when the host app has the
   * Critical Alerts entitlement *and* the user allowed critical alerts. */
  interruptionLevel?: 'passive' | 'active' | 'timeSensitive' | 'critical';
  /** Volume (0..1) for critical alerts. Default 1. */
  criticalVolume?: number;
  /** Present the built-in native call screen after Answer (default true). Set
   * false to render your own screen from the `answered` event. */
  presentCallScreen?: boolean;
  /** Keys used to read call details from a *remote* (APNs) push's userInfo. */
  remotePayloadKeys?: Partial<RemotePayloadKeys>;
  /**
   * Per-push actions for remote calls: the first rule whose `when` entries all
   * equal the push's values wins (e.g. `{ when: { is_test: '1' }, actions: [gotIt] }`).
   * Pushes no rule matches use `defaultActions`.
   */
  remoteActions?: RemoteActionRule[];
  /**
   * Keys of a remote push copied into the event `payload` (stored on disk with
   * every queued event). Default: every custom key except the call's text
   * (`title`, `body`, `speakText`, `fallbackSpeakText` keys).
   */
  remoteEventPayloadKeys?: string[];
  /**
   * Only remote pushes whose payload has one of the listed values for every
   * key here are rung (e.g. `{ user_id: ['42'] }` for the signed-in account).
   * Others are ignored: no call screen, no speech, no events, and the banner
   * is removed. Default: every call push is rung.
   */
  requiredRemotePayload?: Record<string, string[]>;
}

export interface RemoteActionRule {
  when: Record<string, string>;
  actions: CallAction[];
}

export interface RemotePayloadKeys {
  callId: string;
  callerName: string;
  title: string;
  body: string;
  speakText: string;
  language: string;
  fallbackLanguage: string;
  /** Default `fallback_speak_text`: spoken instead of `speakText` when `language` has no voice. */
  fallbackSpeakText: string;
  speechRate: string;
  timeoutSeconds: string;
}

export interface CallReminderConfig {
  /** Android notification channel id for ringing calls (default `call_reminder_incoming`). */
  channelId?: string;
  channelName?: string;
  channelDescription?: string;
  /**
   * Android ring sound: `'default'` (device ringtone), `'alarm'` (device alarm
   * sound), `'notification'`, `'silent'`, or the name of a raw resource
   * (`res/raw/<name>.*`) in the host app.
   */
  ringtone?: string;
  /** Android vibration pattern in ms (`[delay, on, off, on, …]`). Empty array = no vibration. */
  vibrationPattern?: number[];
  /** `#RRGGBB` or `#AARRGGBB`. */
  accentColor?: string;
  backgroundColor?: string;
  textColor?: string;
  /** Brand name shown on the call screen / notification header. */
  appName?: string;
  /** Android drawable/mipmap resource name for the status-bar icon. */
  smallIcon?: string;
  /** Android drawable/mipmap resource name (iOS: asset name) for the caller avatar. */
  largeIcon?: string;
  labels?: Partial<CallReminderLabels>;
  /** Answer/Decline on the ringing call screen (default `swipe`). */
  answerGesture?: AnswerGesture;
  /** Android ringing notification template (default `call`, which can't be swiped away). */
  ringingStyle?: RingingStyle;
  /** Actions shown after answering when a call does not specify its own. */
  defaultActions?: CallAction[];
  /** Android audio usage for the ring: `alarm` (default), `ringtone` or `notification`. */
  ringAudioUsage?: 'alarm' | 'ringtone' | 'notification';
  /** Audio usage for speech: `alarm` (default, loud), `media` or `accessibility`. */
  speechAudioUsage?: 'alarm' | 'media' | 'accessibility';
  /** Ignore a second `showIncomingCall` with the same callId within this window (default 60). */
  duplicateWindowSeconds?: number;
  /** End an answered call this long after speech finished with no action (default 60). */
  idleTimeoutSeconds?: number;
  ios?: CallReminderIosConfig;
}

export interface IncomingCallOptions {
  callId: string;
  /** Brand/caller line, e.g. your app name. Never a fake person or phone number. */
  callerName: string;
  title: string;
  body: string;
  /** Full text spoken after the user answers. */
  speakText: string;
  /** BCP-47 tag, e.g. `hi-IN`. */
  language: string;
  fallbackLanguage?: string;
  /**
   * Spoken instead of `speakText` when the device has no voice for `language`
   * and a fallback voice is used — typically the same message in the
   * `fallbackLanguage`, since text in one script read by another language's
   * voice is unintelligible. The transcript shows what is actually spoken.
   */
  fallbackSpeakText?: string;
  /** 1.0 = normal. */
  speechRate?: number;
  /** 1.0 = normal. */
  pitch?: number;
  /** How many times the text is spoken after answering (default 1, max 5). */
  repeatSpeech?: number;
  /** Ring for this long before reporting `timeout` (default 45, 10..300). */
  timeoutSeconds?: number;
  actions?: CallAction[];
  /** Opaque string map echoed back on every event of this call. */
  payload?: Record<string, string>;
  /** `file://`, `content://`, `android.resource://` URI or a resource/asset name. */
  avatarUri?: string;
  /** Overrides `config.ringtone` for this call. */
  ringtone?: string;
  /**
   * `private`: on a secure, locked Android device the notification and the
   * full-screen call screen show only the app's name, and answering asks the
   * user to unlock first (Android 8+); details appear once unlocked. The
   * reminder is still spoken aloud after answering. iOS follows the user's
   * *Show Previews* setting. Default `public`.
   */
  lockScreenPrivacy?: 'public' | 'private';
}

export type CallPresentation = 'full_screen' | 'heads_up' | 'notification' | 'suppressed';

export interface ShowIncomingCallResult {
  presentation: CallPresentation;
  /**
   * Why the presentation is what it is, e.g. `app_foreground`, `device_in_use`,
   * `full_screen_intent_denied`, `in_phone_call`, `busy`, `duplicate`,
   * `notifications_disabled`, `channel_disabled`, `channel_importance_low`.
   */
  reason?: string;
}

export interface ActiveCall {
  callId: string;
  state: 'ringing' | 'active';
}

export interface SpeakOptions {
  language: string;
  fallbackLanguage?: string;
  rate?: number;
  pitch?: number;
  utteranceId?: string;
}

export interface SpeakResult {
  /** The BCP-47 tag that was actually used. */
  language: string;
  usedFallback: boolean;
}

export type LanguageAvailability = 'available' | 'missing_data' | 'not_supported';

export type CallReminderEventType =
  | 'shown'
  | 'answered'
  | 'declined'
  | 'timeout'
  | 'action'
  | 'speech_started'
  | 'speech_done'
  | 'speech_error'
  | 'ended';

export interface CallReminderEvent {
  /** Unique, stable id — use it to de-duplicate and to acknowledge. */
  id: string;
  type: CallReminderEventType;
  /** Empty string for speech events of a standalone `speak()` call. */
  callId: string;
  actionId?: string;
  presentation?: CallPresentation;
  /** Extra context, e.g. `dismissed`, `api`, `idle`, `replaced`, `process_death`. */
  reason?: string;
  payload: Record<string, string>;
  /** Epoch milliseconds. */
  timestamp: number;
  error?: string;
}

export type CallReminderListener = (event: CallReminderEvent) => void | Promise<void>;
export type CallReminderBackgroundHandler = (event: CallReminderEvent) => Promise<void>;

export interface Subscription {
  remove(): void;
}
