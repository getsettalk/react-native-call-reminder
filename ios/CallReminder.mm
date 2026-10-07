#import <UIKit/UIKit.h>
#import <UserNotifications/UserNotifications.h>

#import <React/RCTInvalidating.h>
#import <RNCallReminderSpec/RNCallReminderSpec.h>

// The generated Swift interface uses UIKit/UserNotifications types, imported
// above so this also compiles where Clang modules are disabled for ObjC++.

#if __has_include("react_native_call_reminder-Swift.h")
#import "react_native_call_reminder-Swift.h"
#else
#import <react_native_call_reminder/react_native_call_reminder-Swift.h>
#endif

static NSString *const kNotApplicable = @"not_applicable";

/**
 * TurboModule glue: conforms to the codegen spec and forwards to the Swift
 * core. The core outlives module instances (a bundle reload creates a new
 * module), so it holds this object only weakly as its event sink.
 */
@interface CallReminder : NativeCallReminderSpecBase <NativeCallReminderSpec, CallReminderEventSink, RCTInvalidating>
@end

@implementation CallReminder {
  BOOL _observing;
}

RCT_EXPORT_MODULE()

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

// The core and UIKit are main-thread only.
- (dispatch_queue_t)methodQueue
{
  return dispatch_get_main_queue();
}

- (instancetype)init
{
  if (self = [super init]) {
    CallReminderCore.shared.sink = self;
  }
  return self;
}

- (void)invalidate
{
  _observing = NO;
  if (CallReminderCore.shared.sink == self) {
    CallReminderCore.shared.sink = nil;
  }
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeCallReminderSpecJSI>(params);
}

#pragma mark - CallReminderEventSink

- (BOOL)callReminderCanEmit
{
  return _observing;
}

- (void)callReminderEmit:(NSDictionary<NSString *, id> *)event
{
  [self emitOnEvent:event];
}

#pragma mark - Configuration & permissions

- (void)configure:(NSDictionary *)config resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared configure:config
                             resolve:resolve
                              reject:^(NSString *code, NSString *message) {
                                reject(code, message, nil);
                              }];
}

- (void)getPermissions:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared getPermissions:resolve];
}

- (void)getDiagnostics:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared getDiagnostics:resolve];
}

- (void)requestNotificationPermission:(BOOL)criticalAlerts
                              resolve:(RCTPromiseResolveBlock)resolve
                               reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared requestNotificationPermission:criticalAlerts resolve:resolve];
}

// Full-screen intents, exact alarms, battery optimisation and OEM auto-start
// are Android concepts.
- (void)requestFullScreenIntentPermission:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(kNotApplicable);
}

- (void)openExactAlarmSettings:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(kNotApplicable);
}

- (void)openBatteryOptimizationSettings:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(kNotApplicable);
}

- (void)openAutoStartSettings:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  resolve(@NO);
}

- (void)openNotificationSettings:(NSString *_Nullable)channelId
                         resolve:(RCTPromiseResolveBlock)resolve
                          reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared openNotificationSettings:resolve];
}

- (void)openAppSettings:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared openAppSettings:resolve];
}

#pragma mark - Calls

- (void)showIncomingCall:(NSDictionary *)options
                 resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared showIncomingCall:options
                                    resolve:resolve
                                     reject:^(NSString *code, NSString *message) {
                                       reject(code, message, nil);
                                     }];
}

- (void)endCall:(NSString *)callId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared endCall:callId resolve:resolve];
}

- (void)getActiveCall:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared getActiveCall:resolve];
}

#pragma mark - Speech

- (void)speak:(NSString *)text
      options:(NSDictionary *)options
      resolve:(RCTPromiseResolveBlock)resolve
       reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared speak:text
                         options:options
                         resolve:resolve
                          reject:^(NSString *code, NSString *message) {
                            reject(code, message, nil);
                          }];
}

- (void)stopSpeaking:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared stopSpeaking:resolve];
}

- (void)isLanguageAvailable:(NSString *)language
                    resolve:(RCTPromiseResolveBlock)resolve
                     reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared isLanguageAvailable:language resolve:resolve];
}

- (void)getAvailableLanguages:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared getAvailableLanguages:resolve];
}

#pragma mark - Events

- (void)getPendingEvents:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared getPendingEvents:resolve];
}

- (void)acknowledgeEvents:(NSArray *)ids resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
  [CallReminderCore.shared acknowledgeEvents:ids resolve:resolve];
}

- (void)setObserving:(BOOL)observing
{
  _observing = observing;
}

// iOS has no headless JS: events that arrive while JS is not running stay in
// the durable queue and are read with getPendingEvents().
- (void)setBackgroundHandlerEnabled:(BOOL)enabled
{
}

@end
