/* eslint-env jest */
/**
 * Jest mock of react-native-call-reminder. The real module looks up its native
 * TurboModule as soon as it is imported, which fails under Jest. Use:
 *
 *   jest.mock('react-native-call-reminder', () =>
 *     require('react-native-call-reminder/jest/mock'),
 *   );
 *
 * Every function is a `jest.fn()` resolving with a neutral value, so tests can
 * override any of them (`mockResolvedValueOnce`, …) and assert calls.
 */

const HEADLESS_TASK_NAME = 'CallReminderBackgroundEvent';

class CallReminderError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'CallReminderError';
    this.code = code;
  }
}

const permissions = () => ({
  platform: 'android',
  osVersion: '15',
  sdkInt: 35,
  manufacturer: 'google',
  notifications: 'granted',
  channel: 'granted',
  fullScreenIntent: 'granted',
  exactAlarm: 'granted',
  batteryOptimization: 'granted',
  autoStart: 'not_applicable',
  oemHasAutoStartManager: false,
  timeSensitive: 'not_applicable',
  criticalAlerts: 'not_applicable',
  lockScreen: 'granted',
  allRequiredGranted: true,
});

const resolved = value => jest.fn(() => Promise.resolve(value));

const CallReminder = {
  HEADLESS_TASK_NAME,
  configure: resolved(undefined),
  getPermissions: jest.fn(() => Promise.resolve(permissions())),
  requestNotificationPermission: resolved('granted'),
  requestFullScreenIntentPermission: resolved('granted'),
  openExactAlarmSettings: resolved('granted'),
  openBatteryOptimizationSettings: resolved('granted'),
  openAutoStartSettings: resolved(false),
  openNotificationSettings: resolved(undefined),
  openAppSettings: resolved(undefined),
  showIncomingCall: resolved({ presentation: 'full_screen' }),
  endCall: resolved(undefined),
  getActiveCall: resolved(null),
  speak: jest.fn((_text, opts) =>
    Promise.resolve({ language: (opts && opts.language) || 'en-US', usedFallback: false }),
  ),
  stopSpeaking: resolved(undefined),
  isLanguageAvailable: resolved('available'),
  getAvailableLanguages: resolved(['en-US']),
  addListener: jest.fn(() => ({ remove: jest.fn() })),
  registerBackgroundHandler: jest.fn(),
  getPendingEvents: resolved([]),
  acknowledgeEvents: resolved(undefined),
};

module.exports = {
  __esModule: true,
  default: CallReminder,
  ...CallReminder,
  CallReminderError,
};
