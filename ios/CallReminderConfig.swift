import UIKit
import UserNotifications

/// Keys of `configure({ labels })` with their generic default copy.
enum Label: String {
  case answer
  case decline
  case incomingTitle
  case speaking
  case listening
  case ended
  case endCall
  case replay
  case tapToAnswer

  var defaultText: String {
    switch self {
    case .answer: return "Answer"
    case .decline: return "Decline"
    case .incomingTitle: return "Incoming reminder call"
    case .speaking: return "Speaking…"
    case .listening: return "Choose an option"
    case .ended: return "Call ended"
    case .endCall: return "End call"
    case .replay: return "Repeat"
    case .tapToAnswer: return "Tap Answer to listen"
    }
  }
}

/**
 * Host-app configuration (`configure()` in JS). Stored as the raw dictionary
 * the app sent and parsed with defaults, so adding a field never breaks an
 * older stored config.
 */
final class CallReminderConfig {
  static let defaultCategoryId = "CALL_REMINDER"
  static let defaultThreadId = "call-reminder"

  /// Keys used to read a call from a remote push's userInfo (`ios.remotePayloadKeys`).
  struct RemoteKeys {
    var callId = "call_id"
    var callerName = "caller_name"
    var title = "title"
    var body = "body"
    var speakText = "speak_text"
    var language = "lang"
    var fallbackLanguage = "fallback_lang"
    var fallbackSpeakText = "fallback_speak_text"
    var speechRate = "speech_rate"
    var timeoutSeconds = "timeout_sec"

    /// Keys holding the call's wording (names, medicines…), kept out of event payloads.
    var textKeys: Set<String> { [title, body, speakText, fallbackSpeakText] }
  }

  /// `ios.remoteActions`: actions for remote pushes whose values match `when`.
  struct RemoteActionRule {
    let when: [String: String]
    let actions: [CallAction]
  }

  let raw: [String: Any]
  let appName: String?
  let largeIcon: String?
  let defaultActions: [CallAction]
  let duplicateWindow: TimeInterval
  let idleTimeout: TimeInterval
  let categoryId: String
  let threadId: String
  let sound: String
  let interruptionLevel: String
  let criticalVolume: Float
  let presentCallScreen: Bool
  let remoteKeys: RemoteKeys
  let remoteActions: [RemoteActionRule]
  /// nil = every custom key except `remoteKeys.textKeys`.
  let remoteEventPayloadKeys: Set<String>?
  /// nil = every call push is accepted.
  let requiredRemotePayload: [String: Set<String>]?
  private let labels: [String: String]
  private let iosLabels: [String: String]
  private let accent: String?
  private let background: String?
  private let text: String?

  init(raw: [String: Any]) {
    self.raw = raw
    let ios = raw["ios"] as? [String: Any] ?? [:]
    appName = raw.nonEmptyString("appName")
    largeIcon = raw.nonEmptyString("largeIcon")
    defaultActions = CallAction.list(raw["defaultActions"])
    duplicateWindow = (raw.double("duplicateWindowSeconds") ?? 60).clamped(0, 86_400)
    idleTimeout = (raw.double("idleTimeoutSeconds") ?? 60).clamped(5, 600)
    labels = raw.stringMap("labels")
    accent = raw.nonEmptyString("accentColor")
    background = raw.nonEmptyString("backgroundColor")
    text = raw.nonEmptyString("textColor")

    categoryId = ios.nonEmptyString("categoryId") ?? Self.defaultCategoryId
    threadId = ios.nonEmptyString("threadId") ?? Self.defaultThreadId
    sound = ios.nonEmptyString("sound") ?? "default"
    interruptionLevel = ios.nonEmptyString("interruptionLevel") ?? "timeSensitive"
    criticalVolume = Float(ios.double("criticalVolume") ?? 1).clamped(0, 1)
    presentCallScreen = (ios["presentCallScreen"] as? Bool) ?? true
    iosLabels = [
      Label.answer.rawValue: ios.nonEmptyString("answerTitle"),
      Label.decline.rawValue: ios.nonEmptyString("declineTitle"),
    ].compactMapValues { $0 }

    let overrides = ios.stringMap("remotePayloadKeys").filter { !$0.value.isEmpty }
    var keys = RemoteKeys()
    keys.callId = overrides["callId"] ?? keys.callId
    keys.callerName = overrides["callerName"] ?? keys.callerName
    keys.title = overrides["title"] ?? keys.title
    keys.body = overrides["body"] ?? keys.body
    keys.speakText = overrides["speakText"] ?? keys.speakText
    keys.language = overrides["language"] ?? keys.language
    keys.fallbackLanguage = overrides["fallbackLanguage"] ?? keys.fallbackLanguage
    keys.fallbackSpeakText = overrides["fallbackSpeakText"] ?? keys.fallbackSpeakText
    keys.speechRate = overrides["speechRate"] ?? keys.speechRate
    keys.timeoutSeconds = overrides["timeoutSeconds"] ?? keys.timeoutSeconds
    remoteKeys = keys

    remoteActions = (ios["remoteActions"] as? [Any] ?? []).compactMap { entry -> RemoteActionRule? in
      guard let rule = entry as? [String: Any] else { return nil }
      let when = rule.stringMap("when")
      guard !when.isEmpty else { return nil }
      return RemoteActionRule(when: when, actions: CallAction.list(rule["actions"]))
    }
    remoteEventPayloadKeys = (ios["remoteEventPayloadKeys"] as? [Any]).map { Set($0.compactMap { $0 as? String }) }
    requiredRemotePayload = (ios["requiredRemotePayload"] as? [String: Any]).map { required in
      required.mapValues { Set(($0 as? [Any] ?? []).compactMap { $0 as? String }) }
    }
  }

  /// The event payload of a remote call: without its wording unless the host
  /// listed the keys, since every queued event is stored on disk.
  func eventPayload(fromRemote values: [String: String]) -> [String: String] {
    if let allowed = remoteEventPayloadKeys {
      return values.filter { allowed.contains($0.key) }
    }
    let textKeys = remoteKeys.textKeys
    return values.filter { !textKeys.contains($0.key) }
  }

  /// Actions of the first `ios.remoteActions` rule the push matches, if any.
  func actions(forRemote values: [String: String]) -> [CallAction]? {
    remoteActions.first { rule in rule.when.allSatisfy { values[$0.key] == $0.value } }?.actions
  }

  /// Whether `ios.requiredRemotePayload` lets this push ring (a missing key counts as "").
  func accepts(remote values: [String: String]) -> Bool {
    guard let required = requiredRemotePayload else { return true }
    return required.allSatisfy { key, allowed in allowed.contains(values[key] ?? "") }
  }

  func label(_ label: Label) -> String {
    if let value = labels[label.rawValue], !value.isEmpty { return value }
    return label.defaultText
  }

  /// Notification action titles: `ios.answerTitle`/`declineTitle`, else the labels.
  func actionTitle(_ label: Label) -> String {
    iosLabels[label.rawValue] ?? self.label(label)
  }

  var accentColor: UIColor { Self.color(accent) ?? UIColor(red: 0.18, green: 0.5, blue: 0.93, alpha: 1) }
  var backgroundColor: UIColor { Self.color(background) ?? UIColor(red: 0.055, green: 0.1, blue: 0.14, alpha: 1) }
  var textColor: UIColor { Self.color(text) ?? .white }
  static let answerColor = UIColor(red: 0.13, green: 0.63, blue: 0.42, alpha: 1)
  static let declineColor = UIColor(red: 0.9, green: 0.28, blue: 0.3, alpha: 1)

  /// Brand line: configured app name, else the host app's display name.
  var brandName: String {
    if let appName { return appName }
    let info = Bundle.main.infoDictionary ?? [:]
    return (info["CFBundleDisplayName"] as? String) ?? (info["CFBundleName"] as? String) ?? ""
  }

  /// `#RRGGBB` or `#AARRGGBB` (the Android convention, shared with JS validation).
  static func color(_ value: String?) -> UIColor? {
    guard let value, value.hasPrefix("#") else { return nil }
    let hex = String(value.dropFirst())
    guard hex.count == 6 || hex.count == 8, let number = UInt32(hex, radix: 16) else { return nil }
    let hasAlpha = hex.count == 8
    let alpha = hasAlpha ? CGFloat((number >> 24) & 0xFF) / 255 : 1
    return UIColor(
      red: CGFloat((number >> 16) & 0xFF) / 255,
      green: CGFloat((number >> 8) & 0xFF) / 255,
      blue: CGFloat(number & 0xFF) / 255,
      alpha: alpha)
  }
}

/// Persists the config so a cold launch from a notification response uses it.
/// Thread-safe: notification delegate callbacks may read it off the main thread.
enum ConfigStore {
  private static let key = "com.callreminder.config"
  private static let lock = NSLock()
  private static var cached: CallReminderConfig?

  static func load() -> CallReminderConfig {
    lock.lock()
    defer { lock.unlock() }
    if let cached { return cached }
    let raw = JSON.object(UserDefaults.standard.data(forKey: key)) as? [String: Any] ?? [:]
    let config = CallReminderConfig(raw: raw)
    cached = config
    return config
  }

  @discardableResult
  static func save(_ raw: [String: Any]) -> CallReminderConfig {
    lock.lock()
    defer { lock.unlock() }
    if let data = JSON.data(raw) {
      UserDefaults.standard.set(data, forKey: key)
    }
    let config = CallReminderConfig(raw: raw)
    cached = config
    return config
  }
}
