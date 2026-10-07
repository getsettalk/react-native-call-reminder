import { TurboModuleRegistry, type CodegenTypes, type TurboModule } from 'react-native';

/**
 * Codegen spec for the native module. This is the raw native surface: loosely
 * typed objects cross the bridge and `index.ts` validates/normalises them into
 * the public, strongly typed API. Do not import this file from app code.
 */
export interface Spec extends TurboModule {
  configure(config: CodegenTypes.UnsafeObject): Promise<void>;
  getPermissions(): Promise<CodegenTypes.UnsafeObject>;
  getDiagnostics(): Promise<CodegenTypes.UnsafeObject>;
  requestNotificationPermission(criticalAlerts: boolean): Promise<string>;
  requestFullScreenIntentPermission(): Promise<string>;
  openExactAlarmSettings(): Promise<string>;
  openBatteryOptimizationSettings(): Promise<string>;
  openAutoStartSettings(): Promise<boolean>;
  openNotificationSettings(channelId: string | null): Promise<void>;
  openAppSettings(): Promise<void>;

  showIncomingCall(options: CodegenTypes.UnsafeObject): Promise<CodegenTypes.UnsafeObject>;
  endCall(callId: string): Promise<void>;
  getActiveCall(): Promise<CodegenTypes.UnsafeObject | null>;

  speak(text: string, options: CodegenTypes.UnsafeObject): Promise<CodegenTypes.UnsafeObject>;
  stopSpeaking(): Promise<void>;
  isLanguageAvailable(language: string): Promise<string>;
  getAvailableLanguages(): Promise<Array<string>>;

  getPendingEvents(): Promise<Array<CodegenTypes.UnsafeObject>>;
  acknowledgeEvents(ids: Array<string>): Promise<void>;

  /** Whether a JS consumer is attached to `onEvent` (live delivery vs. queue/headless). */
  setObserving(observing: boolean): void;
  /** Android: whether a headless background handler is registered in this JS bundle. */
  setBackgroundHandlerEnabled(enabled: boolean): void;

  readonly onEvent: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('CallReminder');
