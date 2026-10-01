import { AppRegistry, Platform } from 'react-native';

import NativeCallReminder from './NativeCallReminder';
import {
  CallReminderError,
  toActiveCall,
  toEvent,
  toLanguageAvailability,
  toPermissionState,
  toPermissions,
  toShowResult,
  toSpeakResult,
  validateConfig,
  validateIncomingCall,
  validateSpeakOptions,
} from './normalize';
import type {
  ActiveCall,
  CallReminderBackgroundHandler,
  CallReminderConfig,
  CallReminderEvent,
  CallReminderListener,
  CallReminderPermissions,
  IncomingCallOptions,
  LanguageAvailability,
  PermissionState,
  ShowIncomingCallResult,
  SpeakOptions,
  SpeakResult,
  Subscription,
} from './types';

export * from './types';
export { CallReminderError } from './normalize';

/**
 * Android headless task name used to deliver events while no JS listener is
 * attached (app killed or in the background). Registered by
 * `registerBackgroundHandler`.
 */
export const HEADLESS_TASK_NAME = 'CallReminderBackgroundEvent';

// ---------------------------------------------------------------------------
// Configuration & permissions
// ---------------------------------------------------------------------------

/**
 * Persist the look, sounds, labels and channel of call reminders. Native code
 * keeps the config on disk, so receivers that run while JS is dead still use
 * it. Call once at startup (it is cheap and idempotent) and again when copy or
 * colours change, e.g. after a language switch.
 */
export async function configure(config: CallReminderConfig): Promise<void> {
  await NativeCallReminder.configure(validateConfig(config));
}

export async function getPermissions(): Promise<CallReminderPermissions> {
  return toPermissions(await NativeCallReminder.getPermissions());
}

/**
 * Android 13+: shows the POST_NOTIFICATIONS prompt (needs a foreground
 * activity). iOS: requests alert/sound/badge, plus critical alerts when
 * `criticalAlerts` is true (requires the Apple-granted entitlement).
 */
export async function requestNotificationPermission(opts?: {
  criticalAlerts?: boolean;
}): Promise<PermissionState> {
  return toPermissionState(
    await NativeCallReminder.requestNotificationPermission(opts?.criticalAlerts === true),
  );
}

/**
 * Android 14+: opens the "full-screen notifications" page for this app and
 * resolves with the re-checked state once the user returns. Resolves at once
 * when already granted or not applicable.
 */
export async function requestFullScreenIntentPermission(): Promise<PermissionState> {
  return toPermissionState(await NativeCallReminder.requestFullScreenIntentPermission());
}

/** Android 12+: "Alarms & reminders" page; resolves with the state on return. */
export async function openExactAlarmSettings(): Promise<PermissionState> {
  return toPermissionState(await NativeCallReminder.openExactAlarmSettings());
}

/**
 * Android: opens the most specific page where the user can change this app's
 * battery usage (the app's *Battery* page where the device has one, else the
 * app's details page, else the battery-optimisation list) and resolves with
 * the re-checked `batteryOptimization` state on return: `denied` only while
 * the app is background-restricted. Resolves at once, without opening
 * anything, when the app is already `unrestricted`. iOS: `not_applicable`.
 */
export async function openBatteryOptimizationSettings(): Promise<PermissionState> {
  return toPermissionState(await NativeCallReminder.openBatteryOptimizationSettings());
}

/** Opens the OEM auto-start manager when one exists. False when none could be opened. */
export async function openAutoStartSettings(): Promise<boolean> {
  return NativeCallReminder.openAutoStartSettings();
}

/** Android: the given channel's settings (or the app's notification settings). iOS: notification settings. */
export async function openNotificationSettings(channelId?: string): Promise<void> {
  await NativeCallReminder.openNotificationSettings(channelId ?? null);
}

export async function openAppSettings(): Promise<void> {
  await NativeCallReminder.openAppSettings();
}

// ---------------------------------------------------------------------------
// Calls
// ---------------------------------------------------------------------------

export async function showIncomingCall(
  options: IncomingCallOptions,
): Promise<ShowIncomingCallResult> {
  return toShowResult(await NativeCallReminder.showIncomingCall(validateIncomingCall(options)));
}

/** Ends a ringing or answered call (emits `ended` with reason `api`). No-op for unknown ids. */
export async function endCall(callId: string): Promise<void> {
  if (typeof callId !== 'string' || callId === '') {
    throw new CallReminderError('invalid_argument', 'callId must be a non-empty string');
  }
  await NativeCallReminder.endCall(callId);
}

export async function getActiveCall(): Promise<ActiveCall | null> {
  return toActiveCall(await NativeCallReminder.getActiveCall());
}

// ---------------------------------------------------------------------------
// Speech
// ---------------------------------------------------------------------------

/**
 * Speaks `text` with the device TTS engine and resolves when it finishes (or
 * is stopped). Falls back to `fallbackLanguage`, then the device language, when
 * the requested voice is missing. Rejects with code `busy` while a call is
 * being spoken.
 */
export async function speak(text: string, opts: SpeakOptions): Promise<SpeakResult> {
  if (typeof text !== 'string' || text.trim() === '') {
    throw new CallReminderError('invalid_argument', 'text must be a non-empty string');
  }
  const options = validateSpeakOptions(opts);
  return toSpeakResult(await NativeCallReminder.speak(text, options), options.language);
}

export async function stopSpeaking(): Promise<void> {
  await NativeCallReminder.stopSpeaking();
}

export async function isLanguageAvailable(language: string): Promise<LanguageAvailability> {
  return toLanguageAvailability(await NativeCallReminder.isLanguageAvailable(language));
}

export async function getAvailableLanguages(): Promise<string[]> {
  return [...(await NativeCallReminder.getAvailableLanguages())];
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------
//
// Every call event is written to a durable native queue *before* it is
// delivered, so nothing is lost if JS is not running. Delivery:
//   1. A listener added with `addListener` gets it live; once every listener
//      has returned (or its promise resolved) without throwing, the event is
//      acknowledged and leaves the queue.
//   2. Otherwise the background handler runs it (and acknowledges on
//      success): live while JS is running (e.g. after a push woke the app in
//      the background), and on Android as a headless task when it is not.
//   3. Anything left over (handler threw, app was killed, …) stays queued:
//      read it with `getPendingEvents()` and `acknowledgeEvents()` it.

const listeners = new Set<CallReminderListener>();
let backgroundHandler: CallReminderBackgroundHandler | null = null;
let nativeSubscription: { remove(): void } | null = null;
let observing = false;

async function deliver(raw: unknown): Promise<void> {
  const event = toEvent(raw);
  if (!event) {
    return;
  }
  const targets: CallReminderListener[] =
    listeners.size > 0 ? [...listeners] : backgroundHandler ? [backgroundHandler] : [];
  if (targets.length === 0) {
    return;
  }
  const results = await Promise.allSettled(
    targets.map(async listener => {
      await listener(event);
    }),
  );
  if (results.every(result => result.status === 'fulfilled')) {
    await acknowledgeEvents([event.id]).catch(() => undefined);
  }
}

function syncNativeObserving(): void {
  // The background handler counts as a consumer on both platforms: while this
  // JS context is alive, events reach it live (deliver() routes to it when no
  // listener is attached). Android only falls back to a headless task when no
  // React instance is running — starting a service from the background is
  // usually refused by then, which would leave events queued.
  const wanted = listeners.size > 0 || backgroundHandler !== null;
  if (wanted && !nativeSubscription) {
    nativeSubscription = NativeCallReminder.onEvent(raw => deliver(raw));
  } else if (!wanted && nativeSubscription) {
    nativeSubscription.remove();
    nativeSubscription = null;
  }
  if (wanted !== observing) {
    observing = wanted;
    NativeCallReminder.setObserving(wanted);
  }
}

export function addListener(listener: CallReminderListener): Subscription {
  if (typeof listener !== 'function') {
    throw new CallReminderError('invalid_argument', 'listener must be a function');
  }
  listeners.add(listener);
  syncNativeObserving();
  let removed = false;
  return {
    remove() {
      if (removed) {
        return;
      }
      removed = true;
      listeners.delete(listener);
      syncNativeObserving();
    },
  };
}

/**
 * Register the handler for events that arrive while no listener is attached.
 * It receives them live whenever this JS context is running (foreground or
 * background).
 * Android: call at module scope in your `index.js` (before
 * `AppRegistry.registerComponent`) — it also registers the `HEADLESS_TASK_NAME`
 * headless task, which runs it when the app was killed.
 * iOS: events that happened while the app was not running are read with
 * `getPendingEvents`.
 */
export function registerBackgroundHandler(handler: CallReminderBackgroundHandler): void {
  if (typeof handler !== 'function') {
    throw new CallReminderError('invalid_argument', 'handler must be a function');
  }
  backgroundHandler = handler;
  if (Platform.OS === 'android') {
    AppRegistry.registerHeadlessTask(HEADLESS_TASK_NAME, () => async (data: unknown) => {
      const event = toEvent(data);
      if (!event || !backgroundHandler) {
        return;
      }
      await backgroundHandler(event);
      await acknowledgeEvents([event.id]).catch(() => undefined);
    });
    NativeCallReminder.setBackgroundHandlerEnabled(true);
  }
  syncNativeObserving();
}

/** Events not yet acknowledged, oldest first. */
export async function getPendingEvents(): Promise<CallReminderEvent[]> {
  const raw = await NativeCallReminder.getPendingEvents();
  return raw.map(toEvent).filter((event): event is CallReminderEvent => event !== null);
}

export async function acknowledgeEvents(ids: string[]): Promise<void> {
  const valid = ids.filter(id => typeof id === 'string' && id !== '');
  if (valid.length > 0) {
    await NativeCallReminder.acknowledgeEvents(valid);
  }
}

const CallReminder = {
  HEADLESS_TASK_NAME,
  configure,
  getPermissions,
  requestNotificationPermission,
  requestFullScreenIntentPermission,
  openExactAlarmSettings,
  openBatteryOptimizationSettings,
  openAutoStartSettings,
  openNotificationSettings,
  openAppSettings,
  showIncomingCall,
  endCall,
  getActiveCall,
  speak,
  stopSpeaking,
  isLanguageAvailable,
  getAvailableLanguages,
  addListener,
  registerBackgroundHandler,
  getPendingEvents,
  acknowledgeEvents,
};

export default CallReminder;
