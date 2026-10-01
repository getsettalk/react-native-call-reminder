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
