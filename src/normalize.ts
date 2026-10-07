import type {
  ActiveCall,
  BatteryUsage,
  CallAction,
  CallPresentation,
  CallReminderConfig,
  CallReminderDiagnostics,
  CallReminderEvent,
  CallReminderEventType,
  CallReminderPermissions,
  ChannelImportance,
  IncomingCallOptions,
  InterruptionFilter,
  IosAuthorizationStatus,
  IosBackgroundRefresh,
  IosDiagnostics,
  IosNotificationSetting,
  LanguageAvailability,
  LockscreenVisibility,
  NotificationChannelInfo,
  PermissionState,
  ProcessExitInfo,
  ProcessExitReason,
  ProcessImportance,
  RemoteActionRule,
  ShowIncomingCallResult,
  SpeakOptions,
  SpeakResult,
  StandbyBucket,
} from './types';

/**
 * Validation of what JS sends to native, and normalisation of what native
 * sends back. Kept free of `react-native` imports so it is trivially testable.
 */

const PERMISSION_STATES: readonly PermissionState[] = [
  'granted',
  'denied',
  'not_determined',
  'not_applicable',
  'unknown',
];
const BATTERY_USAGE: readonly BatteryUsage[] = [
  'unrestricted',
  'optimized',
  'restricted',
  'not_applicable',
  'unknown',
];
const EVENT_TYPES: readonly CallReminderEventType[] = [
  'shown',
  'answered',
  'declined',
  'timeout',
  'action',
  'speech_started',
  'speech_done',
  'speech_error',
  'ended',
];
const PRESENTATIONS: readonly CallPresentation[] = [
  'full_screen',
  'heads_up',
  'notification',
  'suppressed',
];
const LANGUAGE_AVAILABILITY: readonly LanguageAvailability[] = [
  'available',
  'missing_data',
  'not_supported',
];
const STANDBY_BUCKETS: readonly StandbyBucket[] = [
  'exempted',
  'active',
  'working_set',
  'frequent',
  'rare',
  'restricted',
  'never',
  'unknown',
  'not_applicable',
];
const INTERRUPTION_FILTERS: readonly InterruptionFilter[] = [
  'all',
  'priority',
  'alarms',
  'none',
  'unknown',
  'not_applicable',
];
const CHANNEL_IMPORTANCE: readonly ChannelImportance[] = [
  'none',
  'min',
  'low',
  'default',
  'high',
  'max',
  'unspecified',
];
const LOCKSCREEN_VISIBILITY: readonly LockscreenVisibility[] = [
  'public',
  'private',
  'secret',
  'no_override',
  'unknown',
];
const EXIT_REASONS: readonly ProcessExitReason[] = [
  'user_requested',
  'user_stopped',
  'crash',
  'crash_native',
  'anr',
  'low_memory',
  'signaled',
  'excessive_resource_usage',
  'dependency_died',
  'permission_change',
  'initialization_failure',
  'freezer',
  'package_state_change',
  'package_updated',
  'exit_self',
  'other',
  'unknown',
];
const PROCESS_IMPORTANCE: readonly ProcessImportance[] = [
  'foreground',
  'visible',
  'perceptible',
  'service',
  'cached',
  'gone',
  'other',
];
const IOS_AUTHORIZATION: readonly IosAuthorizationStatus[] = [
  'not_determined',
  'denied',
  'authorized',
  'provisional',
  'ephemeral',
  'unknown',
];
const IOS_SETTINGS: readonly IosNotificationSetting[] = [
  'enabled',
  'disabled',
  'not_supported',
  'unknown',
];
const IOS_BACKGROUND_REFRESH: readonly IosBackgroundRefresh[] = [
  'available',
  'denied',
  'restricted',
  'unknown',
];
/** `ApplicationExitInfo` history is capped natively too; never trust a longer list. */
const MAX_EXIT_REASONS = 10;
const ACTION_STYLES = ['primary', 'secondary', 'destructive'] as const;
const COLOR_PATTERN = /^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
// Loose BCP-47 shape check (language[-script][-region][-variant…]); the native
// engines do the real resolution and fall back when a tag is unknown.
const LANGUAGE_TAG_PATTERN = /^[a-zA-Z]{2,8}(?:[-_][a-zA-Z0-9]{1,8})*$/;

export class CallReminderError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'CallReminderError';
    this.code = code;
  }
}

function invalid(message: string): never {
  throw new CallReminderError('invalid_argument', message);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim() === '') {
    invalid(`${field} must be a non-empty string`);
  }
  return value;
}

function optionalString(value: unknown, field: string): string | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (typeof value !== 'string') {
    invalid(`${field} must be a string`);
  }
  return value;
}

function optionalNumber(
  value: unknown,
  field: string,
  min: number,
  max: number,
): number | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    invalid(`${field} must be a finite number`);
  }
  if (value < min || value > max) {
    invalid(`${field} must be between ${min} and ${max}`);
  }
  return value;
}

function optionalEnum<T extends string>(
  value: unknown,
  field: string,
  allowed: readonly T[],
): T | undefined {
  const entry = optionalString(value, field);
  if (entry !== undefined && !(allowed as readonly string[]).includes(entry)) {
    invalid(`${field} must be one of ${allowed.join(', ')}`);
  }
  return entry as T | undefined;
}

function optionalColor(value: unknown, field: string): string | undefined {
  const color = optionalString(value, field);
  if (color !== undefined && !COLOR_PATTERN.test(color)) {
    invalid(`${field} must be #RRGGBB or #AARRGGBB`);
  }
  return color;
}

function languageTag(value: unknown, field: string, required: boolean): string | undefined {
  const tag = required ? requireString(value, field) : optionalString(value, field);
  if (tag !== undefined && !LANGUAGE_TAG_PATTERN.test(tag)) {
    invalid(`${field} must be a BCP-47 language tag such as "en-IN"`);
  }
  return tag?.replace(/_/g, '-');
}

function stringMap(value: unknown, field: string): Record<string, string> {
  if (value === undefined || value === null) {
    return {};
  }
  if (!isRecord(value)) {
    invalid(`${field} must be an object of strings`);
  }
  const out: Record<string, string> = {};
  for (const [key, entry] of Object.entries(value)) {
    if (entry === undefined || entry === null) {
      continue;
    }
    if (typeof entry === 'object') {
      invalid(`${field}.${key} must be a string`);
    }
    out[key] = String(entry);
  }
  return out;
}

/** Like `stringMap` but never throws: non-scalar entries are skipped. */
function lenientStringMap(value: unknown): Record<string, string> {
  const out: Record<string, string> = {};
  if (isRecord(value)) {
    for (const [key, entry] of Object.entries(value)) {
      if (typeof entry === 'string' || typeof entry === 'number' || typeof entry === 'boolean') {
        out[key] = String(entry);
      }
    }
  }
  return out;
}

/** Drop `undefined` so native never sees explicit nulls for omitted fields. */
function compact<T extends Record<string, unknown>>(value: T): T {
  const out: Record<string, unknown> = {};
  for (const [key, entry] of Object.entries(value)) {
    if (entry !== undefined) {
      out[key] = entry;
    }
  }
  return out as T;
}

export function validateActions(value: unknown, field: string): CallAction[] | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (!Array.isArray(value)) {
    invalid(`${field} must be an array`);
  }
  if (value.length > 4) {
    invalid(`${field} supports at most 4 actions`);
  }
  const seen = new Set<string>();
  return value.map((raw, index) => {
    if (!isRecord(raw)) {
      invalid(`${field}[${index}] must be an object`);
    }
    const id = requireString(raw.id, `${field}[${index}].id`);
    if (seen.has(id)) {
      invalid(`${field} contains duplicate id "${id}"`);
    }
    seen.add(id);
    const style = optionalEnum(raw.style, `${field}[${index}].style`, ACTION_STYLES);
    return compact({
      id,
      label: requireString(raw.label, `${field}[${index}].label`),
      style,
      dismissesCall: raw.dismissesCall === undefined ? undefined : Boolean(raw.dismissesCall),
      opensApp: raw.opensApp === undefined ? undefined : Boolean(raw.opensApp),
    });
  });
}

function optionalStringList(value: unknown, field: string): string[] | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (!Array.isArray(value)) {
    invalid(`${field} must be an array of strings`);
  }
  return value.map((entry, index) => requireString(entry, `${field}[${index}]`));
}

function validateRemoteActions(value: unknown): RemoteActionRule[] | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (!Array.isArray(value)) {
    invalid('ios.remoteActions must be an array');
  }
  return value.map((rule, index) => {
    const field = `ios.remoteActions[${index}]`;
    if (!isRecord(rule)) {
      invalid(`${field} must be an object`);
    }
    const when = stringMap(rule.when, `${field}.when`);
    if (Object.keys(when).length === 0) {
      invalid(`${field}.when must match at least one key`);
    }
    return { when, actions: validateActions(rule.actions, `${field}.actions`) ?? [] };
  });
}

function validateRequiredPayload(value: unknown): Record<string, string[]> | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (!isRecord(value)) {
    invalid('ios.requiredRemotePayload must be an object');
  }
  const out: Record<string, string[]> = {};
  for (const [key, entry] of Object.entries(value)) {
    const field = `ios.requiredRemotePayload.${key}`;
    if (!Array.isArray(entry)) {
      invalid(`${field} must be an array of strings`);
    }
    out[key] = entry.map((v, index) => {
      if (typeof v !== 'string') {
        invalid(`${field}[${index}] must be a string`);
      }
      return v;
    });
  }
  return out;
}

export function validateConfig(config: CallReminderConfig): CallReminderConfig {
  if (!isRecord(config)) {
    invalid('config must be an object');
  }
  if (config.vibrationPattern !== undefined) {
    if (
      !Array.isArray(config.vibrationPattern) ||
      config.vibrationPattern.some(v => typeof v !== 'number' || !Number.isFinite(v) || v < 0)
    ) {
      invalid('vibrationPattern must be an array of non-negative numbers');
    }
  }
  const labels = config.labels === undefined ? undefined : stringMap(config.labels, 'labels');
  const ios = config.ios;
  if (ios !== undefined && !isRecord(ios)) {
    invalid('ios must be an object');
  }
  return compact({
    channelId: optionalString(config.channelId, 'channelId'),
    channelName: optionalString(config.channelName, 'channelName'),
    channelDescription: optionalString(config.channelDescription, 'channelDescription'),
    ringtone: optionalString(config.ringtone, 'ringtone'),
    vibrationPattern: config.vibrationPattern?.map(v => Math.round(v)),
    accentColor: optionalColor(config.accentColor, 'accentColor'),
    backgroundColor: optionalColor(config.backgroundColor, 'backgroundColor'),
    textColor: optionalColor(config.textColor, 'textColor'),
    appName: optionalString(config.appName, 'appName'),
    smallIcon: optionalString(config.smallIcon, 'smallIcon'),
    largeIcon: optionalString(config.largeIcon, 'largeIcon'),
    labels,
    answerGesture: optionalEnum(config.answerGesture, 'answerGesture', ['swipe', 'tap'] as const),
    ringingStyle: optionalEnum(config.ringingStyle, 'ringingStyle', ['call', 'standard'] as const),
    defaultActions: validateActions(config.defaultActions, 'defaultActions'),
    ringAudioUsage: optionalEnum(config.ringAudioUsage, 'ringAudioUsage', [
      'alarm',
      'ringtone',
      'notification',
    ] as const),
    speechAudioUsage: optionalEnum(config.speechAudioUsage, 'speechAudioUsage', [
      'alarm',
      'media',
      'accessibility',
    ] as const),
    duplicateWindowSeconds: optionalNumber(
      config.duplicateWindowSeconds,
      'duplicateWindowSeconds',
      0,
      24 * 60 * 60,
    ),
    idleTimeoutSeconds: optionalNumber(config.idleTimeoutSeconds, 'idleTimeoutSeconds', 5, 600),
    ios:
      ios === undefined
        ? undefined
        : compact({
            categoryId: optionalString(ios.categoryId, 'ios.categoryId'),
            threadId: optionalString(ios.threadId, 'ios.threadId'),
            answerTitle: optionalString(ios.answerTitle, 'ios.answerTitle'),
            declineTitle: optionalString(ios.declineTitle, 'ios.declineTitle'),
            sound: optionalString(ios.sound, 'ios.sound'),
            interruptionLevel: optionalEnum(ios.interruptionLevel, 'ios.interruptionLevel', [
              'passive',
              'active',
              'timeSensitive',
              'critical',
            ] as const),
            criticalVolume: optionalNumber(ios.criticalVolume, 'ios.criticalVolume', 0, 1),
            presentCallScreen:
              ios.presentCallScreen === undefined ? undefined : Boolean(ios.presentCallScreen),
            remotePayloadKeys:
              ios.remotePayloadKeys === undefined
                ? undefined
                : stringMap(ios.remotePayloadKeys, 'ios.remotePayloadKeys'),
            remoteActions: validateRemoteActions(ios.remoteActions),
            remoteEventPayloadKeys: optionalStringList(
              ios.remoteEventPayloadKeys,
              'ios.remoteEventPayloadKeys',
            ),
            requiredRemotePayload: validateRequiredPayload(ios.requiredRemotePayload),
          }),
  });
}

export function validateIncomingCall(options: IncomingCallOptions): IncomingCallOptions {
  if (!isRecord(options)) {
    invalid('options must be an object');
  }
  const privacy = optionalEnum(options.lockScreenPrivacy, 'lockScreenPrivacy', [
    'public',
    'private',
  ] as const);
  return compact({
    callId: requireString(options.callId, 'callId'),
    callerName: requireString(options.callerName, 'callerName'),
    title: requireString(options.title, 'title'),
    body: requireString(options.body, 'body'),
    speakText: requireString(options.speakText, 'speakText'),
    language: languageTag(options.language, 'language', true) as string,
    fallbackLanguage: languageTag(options.fallbackLanguage, 'fallbackLanguage', false),
    fallbackSpeakText: optionalString(options.fallbackSpeakText, 'fallbackSpeakText') || undefined,
    speechRate: optionalNumber(options.speechRate, 'speechRate', 0.25, 3),
    pitch: optionalNumber(options.pitch, 'pitch', 0.5, 2),
    repeatSpeech:
      options.repeatSpeech === undefined
        ? undefined
        : Math.round(optionalNumber(options.repeatSpeech, 'repeatSpeech', 1, 5) as number),
    timeoutSeconds:
      options.timeoutSeconds === undefined
        ? undefined
        : Math.round(optionalNumber(options.timeoutSeconds, 'timeoutSeconds', 10, 300) as number),
    actions: validateActions(options.actions, 'actions'),
    payload: stringMap(options.payload, 'payload'),
    avatarUri: optionalString(options.avatarUri, 'avatarUri'),
    ringtone: optionalString(options.ringtone, 'ringtone'),
    lockScreenPrivacy: privacy,
  });
}

export function validateSpeakOptions(options: SpeakOptions): SpeakOptions {
  if (!isRecord(options)) {
    invalid('options must be an object');
  }
  return compact({
    language: languageTag(options.language, 'language', true) as string,
    fallbackLanguage: languageTag(options.fallbackLanguage, 'fallbackLanguage', false),
    rate: optionalNumber(options.rate, 'rate', 0.25, 3),
    pitch: optionalNumber(options.pitch, 'pitch', 0.5, 2),
    utteranceId: optionalString(options.utteranceId, 'utteranceId'),
  });
}

export function toPermissionState(value: unknown): PermissionState {
  return PERMISSION_STATES.includes(value as PermissionState)
    ? (value as PermissionState)
    : 'unknown';
}

export function toBatteryUsage(value: unknown): BatteryUsage {
  return BATTERY_USAGE.includes(value as BatteryUsage) ? (value as BatteryUsage) : 'unknown';
}

export function toPermissions(raw: unknown): CallReminderPermissions {
  const value = isRecord(raw) ? raw : {};
  const platform = value.platform === 'ios' ? 'ios' : 'android';
  return {
    platform,
    osVersion: String(value.osVersion ?? ''),
    sdkInt: typeof value.sdkInt === 'number' ? value.sdkInt : undefined,
    manufacturer: typeof value.manufacturer === 'string' ? value.manufacturer : undefined,
    notifications: toPermissionState(value.notifications),
    channel: toPermissionState(value.channel),
    fullScreenIntent: toPermissionState(value.fullScreenIntent),
    exactAlarm: toPermissionState(value.exactAlarm),
    batteryOptimization: toPermissionState(value.batteryOptimization),
    batteryUsage: toBatteryUsage(value.batteryUsage),
    backgroundRestricted: value.backgroundRestricted === true,
    autoStart: toPermissionState(value.autoStart),
    oemHasAutoStartManager: value.oemHasAutoStartManager === true,
    timeSensitive: toPermissionState(value.timeSensitive),
    criticalAlerts: toPermissionState(value.criticalAlerts),
    lockScreen: toPermissionState(value.lockScreen),
    allRequiredGranted: value.allRequiredGranted === true,
  };
}

// --- Diagnostics -------------------------------------------------------------
// Native output is trusted for nothing: unknown enum values become their
// documented fallback, wrong types become null/false/[], so the result always
// matches `CallReminderDiagnostics` and stays JSON-serialisable.

function oneOf<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return allowed.includes(value as T) ? (value as T) : fallback;
}

function stringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null;
}

function finiteOr(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function toChannel(raw: unknown): NotificationChannelInfo | null {
  if (!isRecord(raw) || typeof raw.id !== 'string' || raw.id === '') {
    return null;
  }
  return {
    id: raw.id,
    name: typeof raw.name === 'string' ? raw.name : '',
    importance: oneOf(raw.importance, CHANNEL_IMPORTANCE, 'unspecified'),
    blocked: raw.blocked === true,
    sound: raw.sound === true,
    vibration: raw.vibration === true,
    bypassDnd: raw.bypassDnd === true,
    lockscreenVisibility: oneOf(raw.lockscreenVisibility, LOCKSCREEN_VISIBILITY, 'unknown'),
  };
}

function toExitInfo(raw: unknown): ProcessExitInfo | null {
  if (!isRecord(raw) || typeof raw.timestamp !== 'number' || !Number.isFinite(raw.timestamp)) {
    return null;
  }
  return {
    timestamp: raw.timestamp,
    reason: oneOf(raw.reason, EXIT_REASONS, 'unknown'),
    status: finiteOr(raw.status, 0),
    importance: oneOf(raw.importance, PROCESS_IMPORTANCE, 'other'),
    description: stringOrNull(raw.description),
    processName: stringOrNull(raw.processName),
  };
}

function toIosDiagnostics(raw: unknown): IosDiagnostics | null {
  if (!isRecord(raw)) {
    return null;
  }
  const settings = isRecord(raw.notificationSettings) ? raw.notificationSettings : {};
  const setting = (key: string) => oneOf(settings[key], IOS_SETTINGS, 'unknown');
  return {
    lowPowerMode: raw.lowPowerMode === true,
    backgroundRefresh: oneOf(raw.backgroundRefresh, IOS_BACKGROUND_REFRESH, 'unknown'),
    notificationSettings: {
      authorizationStatus: oneOf(settings.authorizationStatus, IOS_AUTHORIZATION, 'unknown'),
      alertSetting: setting('alertSetting'),
      soundSetting: setting('soundSetting'),
      lockScreenSetting: setting('lockScreenSetting'),
      notificationCenterSetting: setting('notificationCenterSetting'),
      timeSensitiveSetting: setting('timeSensitiveSetting'),
      criticalAlertSetting: setting('criticalAlertSetting'),
      scheduledDeliverySetting: setting('scheduledDeliverySetting'),
    },
  };
}

export function toDiagnostics(raw: unknown): CallReminderDiagnostics {
  const value = isRecord(raw) ? raw : {};
  const platform = value.platform === 'ios' ? 'ios' : 'android';
  const rom = isRecord(value.rom) ? value.rom : {};
  const tts = isRecord(value.tts) ? value.tts : {};
  const osVersion = typeof value.osVersion === 'string' ? value.osVersion : '';
  const channels = Array.isArray(value.notificationChannels) ? value.notificationChannels : [];
  const exits = Array.isArray(value.exitReasons) ? value.exitReasons : [];
  return {
    platform,
    osVersion,
    sdkInt: typeof value.sdkInt === 'number' && Number.isFinite(value.sdkInt) ? value.sdkInt : null,
    manufacturer: typeof value.manufacturer === 'string' ? value.manufacturer : '',
    brand: typeof value.brand === 'string' ? value.brand : '',
    model: typeof value.model === 'string' ? value.model : '',
    device: typeof value.device === 'string' ? value.device : '',
    rom: {
      name: stringOrNull(rom.name),
      version: stringOrNull(rom.version),
      display: stringOrNull(rom.display),
    },
    permissions: toPermissions(
      isRecord(value.permissions) ? value.permissions : { platform, osVersion },
    ),
    standbyBucket: oneOf(value.standbyBucket, STANDBY_BUCKETS, 'unknown'),
    powerSaveMode: value.powerSaveMode === true,
    deviceIdle: value.deviceIdle === true,
    interruptionFilter: oneOf(value.interruptionFilter, INTERRUPTION_FILTERS, 'unknown'),
    dndAllowsAlarms: typeof value.dndAllowsAlarms === 'boolean' ? value.dndAllowsAlarms : null,
    notificationChannels: channels
      .map(toChannel)
      .filter((channel): channel is NotificationChannelInfo => channel !== null),
    appNotificationsEnabled: value.appNotificationsEnabled === true,
    exitReasons: exits
      .map(toExitInfo)
      .filter((exit): exit is ProcessExitInfo => exit !== null)
      .sort((a, b) => b.timestamp - a.timestamp)
      .slice(0, MAX_EXIT_REASONS),
    forceStoppedRecently: value.forceStoppedRecently === true,
    keyguardSecure: value.keyguardSecure === true,
    tts: {
      engine: stringOrNull(tts.engine),
      defaultLanguage: stringOrNull(tts.defaultLanguage),
    },
    timezone: typeof value.timezone === 'string' ? value.timezone : '',
    locale: typeof value.locale === 'string' ? value.locale : '',
    ios: platform === 'ios' ? toIosDiagnostics(value.ios) : null,
    collectedAt: finiteOr(value.collectedAt, Date.now()),
  };
}

export function toShowResult(raw: unknown): ShowIncomingCallResult {
  const value = isRecord(raw) ? raw : {};
  const presentation = PRESENTATIONS.includes(value.presentation as CallPresentation)
    ? (value.presentation as CallPresentation)
    : 'notification';
  return typeof value.reason === 'string' && value.reason !== ''
    ? { presentation, reason: value.reason }
    : { presentation };
}

export function toActiveCall(raw: unknown): ActiveCall | null {
  if (!isRecord(raw) || typeof raw.callId !== 'string') {
    return null;
  }
  return { callId: raw.callId, state: raw.state === 'active' ? 'active' : 'ringing' };
}

export function toSpeakResult(raw: unknown, requested: string): SpeakResult {
  const value = isRecord(raw) ? raw : {};
  return {
    language: typeof value.language === 'string' ? value.language : requested,
    usedFallback: value.usedFallback === true,
  };
}

export function toLanguageAvailability(value: unknown): LanguageAvailability {
  return LANGUAGE_AVAILABILITY.includes(value as LanguageAvailability)
    ? (value as LanguageAvailability)
    : 'not_supported';
}

/** Returns null for anything that is not a well-formed event (never throws). */
export function toEvent(raw: unknown): CallReminderEvent | null {
  if (!isRecord(raw)) {
    return null;
  }
  const { id, type, callId } = raw;
  if (typeof id !== 'string' || !EVENT_TYPES.includes(type as CallReminderEventType)) {
    return null;
  }
  const event: CallReminderEvent = {
    id,
    type: type as CallReminderEventType,
    callId: typeof callId === 'string' ? callId : '',
    payload: lenientStringMap(raw.payload),
    timestamp: typeof raw.timestamp === 'number' ? raw.timestamp : Date.now(),
  };
  if (typeof raw.actionId === 'string' && raw.actionId !== '') {
    event.actionId = raw.actionId;
  }
  if (PRESENTATIONS.includes(raw.presentation as CallPresentation)) {
    event.presentation = raw.presentation as CallPresentation;
  }
  if (typeof raw.reason === 'string' && raw.reason !== '') {
    event.reason = raw.reason;
  }
  if (typeof raw.error === 'string' && raw.error !== '') {
    event.error = raw.error;
  }
  return event;
}
