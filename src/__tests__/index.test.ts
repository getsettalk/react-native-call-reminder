type Handler = (event: unknown) => unknown;

const mockEmitter: { handler: Handler | null } = { handler: null };
const mockHeadless: { tasks: Map<string, () => (data: unknown) => Promise<void>> } = {
  tasks: new Map(),
};
const mockPlatform = { OS: 'android' as 'android' | 'ios' };

const mockNative = {
  configure: jest.fn(async () => undefined),
  getPermissions: jest.fn(),
  getDiagnostics: jest.fn(),
  requestNotificationPermission: jest.fn(async () => 'granted'),
  requestFullScreenIntentPermission: jest.fn(async () => 'denied'),
  openExactAlarmSettings: jest.fn(async () => 'granted'),
  openBatteryOptimizationSettings: jest.fn(async () => 'bogus'),
  openAutoStartSettings: jest.fn(async () => true),
  openNotificationSettings: jest.fn(async () => undefined),
  openAppSettings: jest.fn(async () => undefined),
  showIncomingCall: jest.fn(),
  endCall: jest.fn(async () => undefined),
  getActiveCall: jest.fn(),
  speak: jest.fn(),
  stopSpeaking: jest.fn(async () => undefined),
  isLanguageAvailable: jest.fn(),
  getAvailableLanguages: jest.fn(async () => ['en-IN', 'hi-IN']),
  getPendingEvents: jest.fn(),
  acknowledgeEvents: jest.fn(async () => undefined),
  setObserving: jest.fn(),
  setBackgroundHandlerEnabled: jest.fn(),
  onEvent: jest.fn((handler: Handler) => {
    mockEmitter.handler = handler;
    return {
      remove: jest.fn(() => {
        mockEmitter.handler = null;
      }),
    };
  }),
};

jest.mock('react-native', () => ({
  Platform: mockPlatform,
  AppRegistry: {
    registerHeadlessTask: (name: string, provider: () => (data: unknown) => Promise<void>) => {
      mockHeadless.tasks.set(name, provider);
    },
  },
  TurboModuleRegistry: { getEnforcing: () => mockNative },
}));

jest.mock('../NativeCallReminder', () => ({ __esModule: true, default: mockNative }));

// Loaded per test so module-level listener state starts clean.
function load(): typeof import('../index') {
  let mod!: typeof import('../index');
  jest.isolateModules(() => {
    mod = require('../index');
  });
  return mod;
}

const validCall = {
  callId: 'c-1',
  callerName: 'Acme Health',
  title: 'Medicine reminder',
  body: 'Time for your 8:00 medicines',
  speakText: 'Hello! It is time to take Metformin, 500 milligrams.',
  language: 'en_IN',
  payload: { userId: 7 as unknown as string, empty: undefined as unknown as string },
};

async function flush(): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
  }
}

beforeEach(() => {
  jest.clearAllMocks();
  mockEmitter.handler = null;
  mockHeadless.tasks.clear();
  mockPlatform.OS = 'android';
});

describe('validation', () => {
  it('normalises incoming call options before they reach native', async () => {
    const api = load();
    mockNative.showIncomingCall.mockResolvedValue({ presentation: 'full_screen' });

    await expect(api.showIncomingCall(validCall)).resolves.toEqual({ presentation: 'full_screen' });

    const sent = mockNative.showIncomingCall.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(sent.language).toBe('en-IN');
    expect(sent.payload).toEqual({ userId: '7' });
    expect(sent).not.toHaveProperty('ringtone');
  });

  it('rejects malformed options with a coded error', async () => {
    const api = load();
    await expect(api.showIncomingCall({ ...validCall, callId: '' })).rejects.toMatchObject({
      code: 'invalid_argument',
    });
    await expect(api.showIncomingCall({ ...validCall, timeoutSeconds: 2 })).rejects.toThrow(
      /timeoutSeconds/,
    );
    await expect(
      api.showIncomingCall({
        ...validCall,
        actions: [
          { id: 'a', label: 'A' },
          { id: 'a', label: 'B' },
        ],
      }),
    ).rejects.toThrow(/duplicate/);
    expect(mockNative.showIncomingCall).not.toHaveBeenCalled();
  });

  it('validates config colours and enums', async () => {
    const api = load();
    await expect(api.configure({ accentColor: 'red' })).rejects.toThrow(/accentColor/);
    await expect(
      api.configure({ ringAudioUsage: 'loud' as unknown as 'alarm' }),
    ).rejects.toThrow(/ringAudioUsage/);
    await api.configure({ accentColor: '#FF0F766E', labels: { answer: 'Listen' } });
    expect(mockNative.configure).toHaveBeenCalledWith({
      accentColor: '#FF0F766E',
      labels: { answer: 'Listen' },
    });
  });

  it('validates the iOS remote-push options', async () => {
    const api = load();
    await expect(
      api.configure({ ios: { remoteActions: [{ when: {}, actions: [] }] } }),
    ).rejects.toThrow(/when/);
    await expect(
      api.configure({ ios: { requiredRemotePayload: { user_id: '7' as unknown as string[] } } }),
    ).rejects.toThrow(/requiredRemotePayload/);
    await api.configure({
      ios: {
        remoteActions: [{ when: { is_test: '1' }, actions: [{ id: 'ok', label: 'Got it' }] }],
        remoteEventPayloadKeys: ['call_id'],
        requiredRemotePayload: { user_id: ['7', ''] },
      },
    });
    expect(mockNative.configure).toHaveBeenLastCalledWith({
      ios: {
        remoteActions: [{ when: { is_test: '1' }, actions: [{ id: 'ok', label: 'Got it' }] }],
        remoteEventPayloadKeys: ['call_id'],
        requiredRemotePayload: { user_id: ['7', ''] },
      },
    });
  });

  it('passes the fallback wording of a call to native', async () => {
    const api = load();
    mockNative.showIncomingCall.mockResolvedValue({ presentation: 'heads_up' });
    await api.showIncomingCall({ ...validCall, fallbackSpeakText: 'Time for Metformin.' });
    await api.showIncomingCall({ ...validCall, callId: 'c-2', fallbackSpeakText: '' });
    const calls = mockNative.showIncomingCall.mock.calls as unknown[][];
    expect((calls[0]?.[0] as Record<string, unknown>).fallbackSpeakText).toBe('Time for Metformin.');
    expect(calls[1]?.[0]).not.toHaveProperty('fallbackSpeakText');
  });

  it('coerces unknown native permission values to "unknown"', async () => {
    const api = load();
    await expect(api.openBatteryOptimizationSettings()).resolves.toBe('unknown');
    mockNative.getPermissions.mockResolvedValue({
      platform: 'android',
      osVersion: '15',
      sdkInt: 35,
      notifications: 'granted',
      channel: 'granted',
      fullScreenIntent: 'denied',
      allRequiredGranted: false,
    });
    const permissions = await api.getPermissions();
    expect(permissions.fullScreenIntent).toBe('denied');
    expect(permissions.timeSensitive).toBe('unknown');
    expect(permissions.oemHasAutoStartManager).toBe(false);
  });

  it('normalises the battery usage fields', async () => {
    const api = load();
    mockNative.getPermissions.mockResolvedValueOnce({
      platform: 'android',
      batteryOptimization: 'granted',
      batteryUsage: 'optimized',
      backgroundRestricted: false,
    });
    await expect(api.getPermissions()).resolves.toMatchObject({
      batteryOptimization: 'granted',
      batteryUsage: 'optimized',
      backgroundRestricted: false,
    });
    mockNative.getPermissions.mockResolvedValueOnce({
      platform: 'android',
      batteryOptimization: 'denied',
      batteryUsage: 'restricted',
      backgroundRestricted: true,
    });
    await expect(api.getPermissions()).resolves.toMatchObject({
      batteryOptimization: 'denied',
      batteryUsage: 'restricted',
      backgroundRestricted: true,
    });
    // Older/odd native values never leak through.
    mockNative.getPermissions.mockResolvedValueOnce({
      platform: 'ios',
      batteryUsage: 'sleepy',
      backgroundRestricted: 'yes',
    });
    await expect(api.getPermissions()).resolves.toMatchObject({
      batteryUsage: 'unknown',
      backgroundRestricted: false,
    });
  });

  it('validates the ringing style', async () => {
    const api = load();
    await expect(api.configure({ ringingStyle: 'fancy' as unknown as 'call' })).rejects.toThrow(/ringingStyle/);
    await api.configure({ ringingStyle: 'standard' });
    expect(mockNative.configure).toHaveBeenLastCalledWith({ ringingStyle: 'standard' });
  });

  it('validates the answer gesture and passes the swipe labels through', async () => {
    const api = load();
    await expect(
      api.configure({ answerGesture: 'drag' as unknown as 'swipe' }),
    ).rejects.toThrow(/answerGesture/);
    await api.configure({
      answerGesture: 'tap',
      labels: { swipeToAnswer: 'Slide up to listen', swipeToDecline: 'Slide up to skip' },
    });
    expect(mockNative.configure).toHaveBeenLastCalledWith({
      answerGesture: 'tap',
      labels: { swipeToAnswer: 'Slide up to listen', swipeToDecline: 'Slide up to skip' },
    });
    await api.configure({});
    expect(mockNative.configure).toHaveBeenLastCalledWith({});
  });

  it('passes a null channel id when none is given', async () => {
    const api = load();
    await api.openNotificationSettings();
    expect(mockNative.openNotificationSettings).toHaveBeenCalledWith(null);
  });
});

describe('events', () => {
  const rawEvent = {
    id: 'e-1',
    type: 'answered',
    callId: 'c-1',
    payload: { userId: '7' },
    timestamp: 1_700_000_000_000,
  };

  it('toggles native observing with the listener count', () => {
    const api = load();
    const a = api.addListener(() => undefined);
    const b = api.addListener(() => undefined);
    expect(mockNative.setObserving).toHaveBeenCalledTimes(1);
    expect(mockNative.setObserving).toHaveBeenLastCalledWith(true);
    a.remove();
    a.remove();
    expect(mockNative.setObserving).toHaveBeenCalledTimes(1);
    b.remove();
    expect(mockNative.setObserving).toHaveBeenLastCalledWith(false);
    expect(mockEmitter.handler).toBeNull();
  });

  it('acknowledges an event once every listener succeeded', async () => {
    const api = load();
    const seen: string[] = [];
    api.addListener(event => {
      seen.push(event.type);
    });
    mockEmitter.handler?.(rawEvent);
    await flush();
    expect(seen).toEqual(['answered']);
    expect(mockNative.acknowledgeEvents).toHaveBeenCalledWith(['e-1']);
  });

  it('keeps an event queued when a listener throws', async () => {
    const api = load();
    api.addListener(() => {
      throw new Error('offline');
    });
    mockEmitter.handler?.(rawEvent);
    await flush();
    expect(mockNative.acknowledgeEvents).not.toHaveBeenCalled();
  });

  it('ignores malformed native events', async () => {
    const api = load();
    const listener = jest.fn();
    api.addListener(listener);
    mockEmitter.handler?.({ id: 'x', type: 'exploded' });
    await flush();
    expect(listener).not.toHaveBeenCalled();
  });

  it('registers an Android headless task that acknowledges on success', async () => {
    const api = load();
    const handler = jest.fn(async () => undefined);
    api.registerBackgroundHandler(handler);

    expect(mockNative.setBackgroundHandlerEnabled).toHaveBeenCalledWith(true);
    const task = mockHeadless.tasks.get(api.HEADLESS_TASK_NAME);
    expect(task).toBeDefined();
    await task?.()(rawEvent);
    expect(handler).toHaveBeenCalledWith(expect.objectContaining({ id: 'e-1', type: 'answered' }));
    expect(mockNative.acknowledgeEvents).toHaveBeenCalledWith(['e-1']);
  });

  it.each(['android', 'ios'] as const)(
    'uses the background handler as the live consumer while JS runs (%s)',
    async os => {
      mockPlatform.OS = os;
      const api = load();
      const handler = jest.fn(async () => undefined);
      api.registerBackgroundHandler(handler);
      // Android also registers the headless task for when JS is not running.
      expect(mockHeadless.tasks.size).toBe(os === 'android' ? 1 : 0);
      expect(mockNative.setObserving).toHaveBeenLastCalledWith(true);

      mockEmitter.handler?.(rawEvent);
      await flush();
      expect(handler).toHaveBeenCalledTimes(1);

      // A foreground listener takes precedence over the background handler.
      const listener = jest.fn();
      api.addListener(listener);
      mockEmitter.handler?.({ ...rawEvent, id: 'e-2' });
      await flush();
      expect(listener).toHaveBeenCalledTimes(1);
      expect(handler).toHaveBeenCalledTimes(1);
    },
  );

  it('filters malformed pending events', async () => {
    const api = load();
    mockNative.getPendingEvents.mockResolvedValue([rawEvent, { nope: true }]);
    const pending = await api.getPendingEvents();
    expect(pending.map(e => e.id)).toEqual(['e-1']);
  });
});

describe('speech', () => {
  it('validates input and normalises the result', async () => {
    const api = load();
    await expect(api.speak('  ', { language: 'en-IN' })).rejects.toMatchObject({
      code: 'invalid_argument',
    });
    mockNative.speak.mockResolvedValue({ language: 'en-US', usedFallback: true });
    await expect(api.speak('Hello', { language: 'xx-YY', fallbackLanguage: 'en-US' })).resolves.toEqual(
      { language: 'en-US', usedFallback: true },
    );
    mockNative.isLanguageAvailable.mockResolvedValue('weird');
    await expect(api.isLanguageAvailable('hi-IN')).resolves.toBe('not_supported');
  });
});

describe('diagnostics', () => {
  // What a realme phone (realme UI, Android 14) reports after an OEM swipe kill.
  const realme = {
    platform: 'android',
    osVersion: '14',
    sdkInt: 34,
    manufacturer: 'realme',
    brand: 'realme',
    model: 'RMX3686',
    device: 'RE58B2L1',
    rom: { name: 'realme UI', version: 'V5.0', display: 'RMX3686_14.0.0.600(EX01)' },
    permissions: {
      platform: 'android',
      osVersion: '14',
      sdkInt: 34,
      manufacturer: 'realme',
      notifications: 'granted',
      channel: 'granted',
      fullScreenIntent: 'granted',
      exactAlarm: 'not_applicable',
      batteryOptimization: 'granted',
      batteryUsage: 'optimized',
      backgroundRestricted: false,
      autoStart: 'unknown',
      oemHasAutoStartManager: true,
      timeSensitive: 'not_applicable',
      criticalAlerts: 'not_applicable',
      lockScreen: 'granted',
      allRequiredGranted: true,
    },
    standbyBucket: 'active',
    powerSaveMode: false,
    deviceIdle: false,
    interruptionFilter: 'all',
    dndAllowsAlarms: true,
    notificationChannels: [
      {
        id: 'call_reminder_incoming',
        name: 'Reminder calls',
        importance: 'high',
        blocked: false,
        sound: true,
        vibration: true,
        bypassDnd: false,
        lockscreenVisibility: 'public',
      },
      {
        id: 'reminders',
        name: 'Medicine reminders',
        importance: 'none',
        blocked: true,
        sound: true,
        vibration: true,
        bypassDnd: false,
        lockscreenVisibility: 'no_override',
      },
    ],
    appNotificationsEnabled: true,
    exitReasons: [
      {
        timestamp: 1_767_000_000_000,
        reason: 'low_memory',
        status: 0,
        importance: 'cached',
        description: null,
        processName: 'com.example.app',
      },
      {
        timestamp: 1_767_100_000_000,
        reason: 'user_requested',
        status: 0,
        importance: 'cached',
        description: 'stop com.example.app due to from pid 2786',
        processName: 'com.example.app',
      },
    ],
    forceStoppedRecently: true,
    keyguardSecure: true,
    tts: { engine: 'com.google.android.tts', defaultLanguage: 'hi-IN' },
    timezone: 'Asia/Kolkata',
    locale: 'en-IN',
    ios: null,
    collectedAt: 1_767_200_000_000,
  };

  it('passes a well-formed Android snapshot through, newest exit first', async () => {
    const api = load();
    mockNative.getDiagnostics.mockResolvedValueOnce(realme);
    const diagnostics = await api.getDiagnostics();
    expect(diagnostics).toEqual({
      ...realme,
      exitReasons: [realme.exitReasons[1], realme.exitReasons[0]],
    });
    expect(diagnostics.permissions.oemHasAutoStartManager).toBe(true);
    // Plain JSON, ready for a backend: nothing is lost in a round trip.
    expect(JSON.parse(JSON.stringify(diagnostics))).toEqual(diagnostics);
  });

  it('coerces unknown or malformed native values instead of failing', async () => {
    const api = load();
    mockNative.getDiagnostics.mockResolvedValueOnce({
      ...realme,
      sdkInt: '34',
      rom: { name: '', version: 5, display: 'X' },
      standbyBucket: 'sleepy',
      interruptionFilter: 7,
      dndAllowsAlarms: 'yes',
      powerSaveMode: 'true',
      notificationChannels: [
        { id: 'a', name: 7, importance: 'urgent', lockscreenVisibility: 'hidden', blocked: 1 },
        { name: 'no id' },
        'junk',
      ],
      exitReasons: [
        ...Array.from({ length: 12 }, (_, i) => ({
          timestamp: 1_000 + i,
          reason: i === 11 ? 'zapped' : 'anr',
          status: 'x',
          importance: 'background',
        })),
        { timestamp: 'never', reason: 'crash' },
      ],
      tts: 'google',
      ios: { lowPowerMode: true },
    });
    const diagnostics = await api.getDiagnostics();
    expect(diagnostics.sdkInt).toBeNull();
    expect(diagnostics.rom).toEqual({ name: null, version: null, display: 'X' });
    expect(diagnostics.standbyBucket).toBe('unknown');
    expect(diagnostics.interruptionFilter).toBe('unknown');
    expect(diagnostics.dndAllowsAlarms).toBeNull();
    expect(diagnostics.powerSaveMode).toBe(false);
    expect(diagnostics.notificationChannels).toEqual([
      {
        id: 'a',
        name: '',
        importance: 'unspecified',
        blocked: false,
        sound: false,
        vibration: false,
        bypassDnd: false,
        lockscreenVisibility: 'unknown',
      },
    ]);
    expect(diagnostics.exitReasons).toHaveLength(10);
    expect(diagnostics.exitReasons[0]).toEqual({
      timestamp: 1_011,
      reason: 'unknown',
      status: 0,
      importance: 'other',
      description: null,
      processName: null,
    });
    expect(diagnostics.tts).toEqual({ engine: null, defaultLanguage: null });
    // The iOS block only exists on iOS.
    expect(diagnostics.ios).toBeNull();
  });

  it('normalises an iOS snapshot', async () => {
    mockPlatform.OS = 'ios';
    const api = load();
    mockNative.getDiagnostics.mockResolvedValueOnce({
      platform: 'ios',
      osVersion: '18.0',
      sdkInt: null,
      manufacturer: 'apple',
      brand: 'apple',
      model: 'iPhone15,2',
      device: 'iPhone',
      rom: { name: null, version: null, display: 'Version 18.0 (Build 22A3354)' },
      permissions: { platform: 'ios', osVersion: '18.0', notifications: 'granted' },
      standbyBucket: 'not_applicable',
      powerSaveMode: true,
      deviceIdle: false,
      interruptionFilter: 'not_applicable',
      dndAllowsAlarms: null,
      notificationChannels: [],
      appNotificationsEnabled: true,
      exitReasons: [],
      forceStoppedRecently: false,
      keyguardSecure: true,
      tts: { engine: 'AVSpeechSynthesizer', defaultLanguage: 'en-IN' },
      timezone: 'Asia/Kolkata',
      locale: 'en-IN',
      ios: {
        lowPowerMode: true,
        backgroundRefresh: 'denied',
        notificationSettings: {
          authorizationStatus: 'authorized',
          alertSetting: 'enabled',
          soundSetting: 'enabled',
          lockScreenSetting: 'enabled',
          notificationCenterSetting: 'enabled',
          timeSensitiveSetting: 'disabled',
          criticalAlertSetting: 'not_supported',
          scheduledDeliverySetting: 'mystery',
        },
      },
      collectedAt: 1_767_200_000_000,
    });
    const diagnostics = await api.getDiagnostics();
    expect(diagnostics.platform).toBe('ios');
    expect(diagnostics.sdkInt).toBeNull();
    expect(diagnostics.permissions.platform).toBe('ios');
    expect(diagnostics.permissions.timeSensitive).toBe('unknown');
    expect(diagnostics.standbyBucket).toBe('not_applicable');
    expect(diagnostics.ios).toEqual({
      lowPowerMode: true,
      backgroundRefresh: 'denied',
      notificationSettings: {
        authorizationStatus: 'authorized',
        alertSetting: 'enabled',
        soundSetting: 'enabled',
        lockScreenSetting: 'enabled',
        notificationCenterSetting: 'enabled',
        timeSensitiveSetting: 'disabled',
        criticalAlertSetting: 'not_supported',
        scheduledDeliverySetting: 'unknown',
      },
    });
  });

  it('never throws on an empty or non-object native result', async () => {
    const api = load();
    mockNative.getDiagnostics.mockResolvedValueOnce(null);
    const diagnostics = await api.getDiagnostics();
    expect(diagnostics).toMatchObject({
      platform: 'android',
      osVersion: '',
      sdkInt: null,
      rom: { name: null, version: null, display: null },
      standbyBucket: 'unknown',
      interruptionFilter: 'unknown',
      dndAllowsAlarms: null,
      notificationChannels: [],
      exitReasons: [],
      forceStoppedRecently: false,
      ios: null,
    });
    expect(diagnostics.permissions.notifications).toBe('unknown');
    expect(typeof diagnostics.collectedAt).toBe('number');
  });
});

describe('jest mock', () => {
  it('mocks every public function, and getDiagnostics resolves a normalised snapshot', async () => {
    const api = load();
    const mock = require('../../jest/mock') as {
      default: Record<string, unknown>;
      getDiagnostics: () => Promise<unknown>;
    };
    expect(Object.keys(mock.default).sort()).toEqual(Object.keys(api.default).sort());
    mockNative.getDiagnostics.mockResolvedValueOnce(await mock.getDiagnostics());
    const normalised = await api.getDiagnostics();
    expect(normalised).toEqual(await mock.getDiagnostics());
  });
});
