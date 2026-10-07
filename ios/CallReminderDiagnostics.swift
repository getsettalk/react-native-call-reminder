import AVFoundation
import LocalAuthentication
import UIKit
import UserNotifications

/**
 * `getDiagnostics()` on iOS: the platform-neutral fields of
 * `CallReminderDiagnostics` (JS) plus its `ios` block. Android-only concepts
 * are `not_applicable` / null / false / []. Every value comes from a
 * non-throwing system API, needs no permission and never prompts the user.
 * Main thread only (UIKit reads).
 */
enum CallReminderDiagnostics {
  private static let notApplicable = "not_applicable"

  static func snapshot(_ settings: UNNotificationSettings) -> [String: Any] {
    let device = UIDevice.current
    let process = ProcessInfo.processInfo
    let lowPowerMode = process.isLowPowerModeEnabled
    return [
      "platform": "ios",
      "osVersion": device.systemVersion,
      "sdkInt": NSNull(),
      "manufacturer": "apple",
      "brand": "apple",
      "model": modelIdentifier(),
      "device": device.model,
      "rom": [
        "name": NSNull(),
        "version": NSNull(),
        // e.g. "Version 18.0 (Build 22A3354)".
        "display": process.operatingSystemVersionString,
      ] as [String: Any],
      "permissions": CallReminderCore.permissions(settings),
      "standbyBucket": notApplicable,
      "powerSaveMode": lowPowerMode,
      "deviceIdle": false,
      // Focus state needs the Communication Notifications / Focus Status entitlement.
      "interruptionFilter": notApplicable,
      "dndAllowsAlarms": NSNull(),
      "notificationChannels": [Any](),
      "appNotificationsEnabled": CallReminderCore.isAuthorized(settings.authorizationStatus),
      "exitReasons": [Any](),
      "forceStoppedRecently": false,
      "keyguardSecure": passcodeSet(),
      "tts": [
        "engine": "AVSpeechSynthesizer",
        "defaultLanguage": AVSpeechSynthesisVoice.currentLanguageCode(),
      ],
      "timezone": TimeZone.current.identifier,
      "locale": localeTag(),
      "ios": [
        "lowPowerMode": lowPowerMode,
        "backgroundRefresh": backgroundRefresh(UIApplication.shared.backgroundRefreshStatus),
        "notificationSettings": notificationSettings(settings),
      ] as [String: Any],
      "collectedAt": (Date().timeIntervalSince1970 * 1000).rounded(),
    ]
  }

  private static func notificationSettings(_ settings: UNNotificationSettings) -> [String: Any] {
    [
      "authorizationStatus": authorizationStatus(settings.authorizationStatus),
      "alertSetting": setting(settings.alertSetting),
      "soundSetting": setting(settings.soundSetting),
      "lockScreenSetting": setting(settings.lockScreenSetting),
      "notificationCenterSetting": setting(settings.notificationCenterSetting),
      "timeSensitiveSetting": setting(settings.timeSensitiveSetting),
      "criticalAlertSetting": setting(settings.criticalAlertSetting),
      "scheduledDeliverySetting": setting(settings.scheduledDeliverySetting),
    ]
  }

  private static func authorizationStatus(_ status: UNAuthorizationStatus) -> String {
    switch status {
    case .notDetermined: return "not_determined"
    case .denied: return "denied"
    case .authorized: return "authorized"
    case .provisional: return "provisional"
    case .ephemeral: return "ephemeral"
    @unknown default: return PermissionState.unknown
    }
  }

  private static func setting(_ setting: UNNotificationSetting) -> String {
    switch setting {
    case .enabled: return "enabled"
    case .disabled: return "disabled"
    case .notSupported: return "not_supported"
    @unknown default: return PermissionState.unknown
    }
  }

  private static func backgroundRefresh(_ status: UIBackgroundRefreshStatus) -> String {
    switch status {
    case .available: return "available"
    case .denied: return "denied"
    case .restricted: return "restricted"
    @unknown default: return PermissionState.unknown
    }
  }

  /// The hardware model identifier, e.g. `iPhone15,2` (the simulated one on a simulator).
  private static func modelIdentifier() -> String {
    if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"], !simulated.isEmpty {
      return simulated
    }
    var info = utsname()
    guard uname(&info) == 0 else { return UIDevice.current.model }
    let identifier = withUnsafeBytes(of: &info.machine) { bytes in
      String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)
    }
    return identifier.isEmpty ? UIDevice.current.model : identifier
  }

  /// A passcode is set. Only asks whether authentication is possible: no prompt,
  /// no Face ID usage description needed.
  private static func passcodeSet() -> Bool {
    LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
  }

  /// `en_IN@calendar=…` → `en-IN`.
  private static func localeTag() -> String {
    let identifier = Locale.current.identifier
    let base = identifier.split(separator: "@", maxSplits: 1).first.map(String.init) ?? identifier
    return base.replacingOccurrences(of: "_", with: "-")
  }
}
