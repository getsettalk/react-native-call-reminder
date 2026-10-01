import Foundation

/// Permission/setting states as reported to JS (`PermissionState`).
enum PermissionState {
  static let granted = "granted"
  static let denied = "denied"
  static let notDetermined = "not_determined"
  static let notApplicable = "not_applicable"
  static let unknown = "unknown"
}

/// Presentation values and reasons shared with the Android implementation.
enum Presentation {
  static let fullScreen = "full_screen"
  static let headsUp = "heads_up"
  static let notification = "notification"
  static let suppressed = "suppressed"
}

enum Reason {
  static let appForeground = "app_foreground"
  static let duplicate = "duplicate"
  static let notificationsDisabled = "notifications_disabled"
  static let alertsDisabled = "alerts_disabled"
  static let timeSensitiveDisabled = "time_sensitive_disabled"
  static let timeSensitive = "time_sensitive"
  static let critical = "critical"
  static let active = "active"
  static let passive = "passive"
  static let postFailed = "post_failed"
  static let dismissed = "dismissed"
  static let replaced = "replaced"
  static let api = "api"
  static let user = "user"
  static let action = "action"
  static let idle = "idle"
  static let processDeath = "process_death"
  static let fallbackLanguage = "fallback_language"
}

/// A button on the call screen after answering (e.g. "Taken", "Snooze").
struct CallAction: Equatable {
  static let stylePrimary = "primary"
  static let styleSecondary = "secondary"
  static let styleDestructive = "destructive"

  let id: String
  let label: String
  let style: String
  let dismissesCall: Bool
  let opensApp: Bool

  init?(json: [String: Any]) {
    guard let id = json.nonEmptyString("id"), let label = json.nonEmptyString("label") else { return nil }
    self.id = id
    self.label = label
    if let style = json["style"] as? String,
       [Self.stylePrimary, Self.styleSecondary, Self.styleDestructive].contains(style) {
      self.style = style
    } else {
      self.style = Self.styleSecondary
    }
    dismissesCall = (json["dismissesCall"] as? Bool) ?? true
    opensApp = (json["opensApp"] as? Bool) ?? false
  }

  var json: [String: Any] {
    ["id": id, "label": label, "style": style, "dismissesCall": dismissesCall, "opensApp": opensApp]
  }

  static func list(_ value: Any?) -> [CallAction] {
    (value as? [Any])?.compactMap { ($0 as? [String: Any]).flatMap(CallAction.init(json:)) } ?? []
  }
}

/// One reminder call, as passed to `showIncomingCall` or read from a remote push.
struct IncomingCall {
  static let defaultTimeoutSeconds = 45

  let callId: String
  let callerName: String
  let title: String
  let body: String
  let speakText: String
  let language: String
  let fallbackLanguage: String?
  /// Spoken (in the fallback voice) instead of `speakText` when `language` has no voice.
  let fallbackSpeakText: String?
  let speechRate: Float
  let pitch: Float
  let repeatSpeech: Int
  let timeoutSeconds: Int
  /// nil = use the configured default actions.
  let actions: [CallAction]?
  let payload: [String: String]
  let avatarUri: String?
  let ringtone: String?
  let privateOnLockScreen: Bool

  enum ParseError: Error {
    case missing(String)
  }

  init(json: [String: Any]) throws {
    func required(_ key: String) throws -> String {
      guard let value = json.nonEmptyString(key) else { throw ParseError.missing(key) }
      return value
    }
    callId = try required("callId")
    callerName = try required("callerName")
    title = try required("title")
    body = try required("body")
    speakText = try required("speakText")
    language = try required("language").replacingOccurrences(of: "_", with: "-")
    fallbackLanguage = json.nonEmptyString("fallbackLanguage")?.replacingOccurrences(of: "_", with: "-")
    fallbackSpeakText = json.nonEmptyString("fallbackSpeakText")
    speechRate = Float(json.double("speechRate") ?? 1).clamped(0.25, 3)
    pitch = Float(json.double("pitch") ?? 1).clamped(0.5, 2)
    repeatSpeech = (json.int("repeatSpeech") ?? 1).clamped(1, 5)
    timeoutSeconds = (json.int("timeoutSeconds") ?? Self.defaultTimeoutSeconds).clamped(10, 300)
    actions = (json["actions"] as? [Any]).map { CallAction.list($0) }
    payload = json.stringMap("payload")
    avatarUri = json.nonEmptyString("avatarUri")
    ringtone = json.nonEmptyString("ringtone")
    privateOnLockScreen = json.nonEmptyString("lockScreenPrivacy") == "private"
  }

  var json: [String: Any] {
    var out: [String: Any] = [
      "callId": callId,
      "callerName": callerName,
      "title": title,
      "body": body,
      "speakText": speakText,
      "language": language,
      "speechRate": Double(speechRate),
      "pitch": Double(pitch),
      "repeatSpeech": repeatSpeech,
      "timeoutSeconds": timeoutSeconds,
      "payload": payload,
      "lockScreenPrivacy": privateOnLockScreen ? "private" : "public",
    ]
    out["fallbackLanguage"] = fallbackLanguage
    out["fallbackSpeakText"] = fallbackSpeakText
    out["actions"] = actions?.map(\.json)
    out["avatarUri"] = avatarUri
    out["ringtone"] = ringtone
    return out
  }

  func resolvedActions(_ config: CallReminderConfig) -> [CallAction] {
    actions ?? config.defaultActions
  }
}

/// An event delivered to JS (`CallReminderEvent`).
struct CallEvent {
  static let shown = "shown"
  static let answered = "answered"
  static let declined = "declined"
  static let timeout = "timeout"
  static let action = "action"
  static let speechStarted = "speech_started"
  static let speechDone = "speech_done"
  static let speechError = "speech_error"
  static let ended = "ended"

  let id: String
  let type: String
  let callId: String
  let payload: [String: String]
  /// Epoch milliseconds.
  let timestamp: Double
  var actionId: String?
  var presentation: String?
  var reason: String?
  var error: String?

  init(
    type: String,
    call: IncomingCall?,
    callId: String? = nil,
    actionId: String? = nil,
    presentation: String? = nil,
    reason: String? = nil,
    error: String? = nil,
    payload: [String: String]? = nil
  ) {
    id = UUID().uuidString.lowercased()
    self.type = type
    self.callId = callId ?? call?.callId ?? ""
    self.payload = payload ?? call?.payload ?? [:]
    timestamp = (Date().timeIntervalSince1970 * 1000).rounded()
    self.actionId = actionId
    self.presentation = presentation
    self.reason = reason
    self.error = error
  }

  init?(dictionary: [String: Any]) {
    guard let id = dictionary.nonEmptyString("id"), let type = dictionary.nonEmptyString("type") else { return nil }
    self.id = id
    self.type = type
    callId = (dictionary["callId"] as? String) ?? ""
    payload = dictionary.stringMap("payload")
    timestamp = dictionary.double("timestamp") ?? 0
    actionId = dictionary.nonEmptyString("actionId")
    presentation = dictionary.nonEmptyString("presentation")
    reason = dictionary.nonEmptyString("reason")
    error = dictionary.nonEmptyString("error")
  }

  var dictionary: [String: Any] {
    var out: [String: Any] = ["id": id, "type": type, "callId": callId, "payload": payload, "timestamp": timestamp]
    out["actionId"] = actionId
    out["presentation"] = presentation
    out["reason"] = reason
    out["error"] = error
    return out
  }
}

// MARK: - Helpers

extension Dictionary where Key == String, Value == Any {
  func nonEmptyString(_ key: String) -> String? {
    guard let value = self[key] as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
      return nil
    }
    return value
  }

  /// Numbers arrive as NSNumber from JS and as strings from remote pushes.
  func double(_ key: String) -> Double? {
    let value: Double?
    switch self[key] {
    case let number as NSNumber: value = number.doubleValue
    case let string as String: value = Double(string.trimmingCharacters(in: .whitespaces))
    default: value = nil
    }
    return value.flatMap { $0.isFinite ? $0 : nil }
  }

  func int(_ key: String) -> Int? {
    double(key).flatMap { abs($0) < Double(Int32.max) ? Int($0.rounded()) : nil }
  }

  func stringMap(_ key: String) -> [String: String] {
    guard let source = self[key] as? [String: Any] else { return [:] }
    var out: [String: String] = [:]
    for (name, value) in source {
      switch value {
      case let string as String: out[name] = string
      case let number as NSNumber: out[name] = number.stringValue
      default: continue
      }
    }
    return out
  }
}

extension Comparable {
  func clamped(_ lower: Self, _ upper: Self) -> Self {
    Swift.min(Swift.max(self, lower), upper)
  }
}

enum JSON {
  static func data(_ object: Any) -> Data? {
    guard JSONSerialization.isValidJSONObject(object) else { return nil }
    return try? JSONSerialization.data(withJSONObject: object)
  }

  static func object(_ data: Data?) -> Any? {
    guard let data else { return nil }
    return try? JSONSerialization.jsonObject(with: data)
  }
}
