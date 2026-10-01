import AVFoundation
import UIKit
import UserNotifications

/// Implemented by the TurboModule (`CallReminder.mm`) to receive live events.
@objc(CallReminderEventSink)
public protocol CallReminderEventSink: AnyObject {
  /// True while a JS listener is attached.
  func callReminderCanEmit() -> Bool
  func callReminderEmit(_ event: [String: Any])
}

/**
 * The iOS call state machine and the native side of the JS API. It never
 * touches `UNUserNotificationCenter.delegate`: the host app forwards responses
 * through `CallReminderNotifications` (see README), so it coexists with
 * Notifee/notify-kit and Firebase Messaging, which chain the delegate.
 *
 * ```
 *          show() / remote push
 *                 │
 *              RINGING ── decline / dismiss ──► declined
 *                 │   └── deadline ───────────► timeout
 *          answer │
 *                 ▼
 *              ACTIVE ── action / end / idle ─► ended
 * ```
 *
 * Every transition is persisted before side effects run, so a launch caused
 * by a notification response can pick up where a previous launch stopped.
 * Main thread only.
 */
@objc(CallReminderCore)
public final class CallReminderCore: NSObject {
  @objc public static let shared = CallReminderCore()

  @objc public weak var sink: CallReminderEventSink?

  public typealias Resolve = (Any?) -> Void
  public typealias Reject = (String, String) -> Void

  enum SpeechStatus {
    case idle
    case speaking
    case done
    case error
  }

  static let answerActionId = "com.callreminder.answer"
  static let declineActionId = "com.callreminder.decline"
  static let userInfoCallKey = "com.callreminder.call"
  private static let requestPrefix = "com.callreminder."
  /// Identifies this launch; an ACTIVE call owned by another launch lost its speech.
  private static let launchId = UUID().uuidString
  /// A dismissal this close to the deadline is a timeout, not the user.
  private static let timeoutSlack: TimeInterval = 2

  let store = CallStore()
  private let events = EventStore()
  private let speech = CallReminderSpeech()
  private let ringer = CallReminderRinger()
  private let center = UNUserNotificationCenter.current()
  private var started = false
  private var timeouts: [String: DispatchWorkItem] = [:]
  private var idleWork: DispatchWorkItem?
  private var speechStatuses: [String: SpeechStatus] = [:]
  /// What is actually spoken when it differs from the call's speakText (fallback wording).
  private var spokenTexts: [String: String] = [:]
  private var speakingCallId: String?
  private var speechToken = 0
  /// Speech (and the call screen) can only start once the app is active.
  private var pendingSpeech: (callId: String, repeatCount: Int)?
  private var pendingScreenCallId: String?
  private weak var screen: CallScreenViewController?

  override private init() {
    super.init()
  }

  /// Idempotent: registers the category, observes the app lifecycle and
  /// reconciles calls left behind by a previous launch.
  func start() {
    guard !started else { return }
    started = true
    NotificationCenter.default.addObserver(
      self, selector: #selector(appDidBecomeActive), name: UIApplication.didBecomeActiveNotification, object: nil)
    NotificationCenter.default.addObserver(
      self, selector: #selector(appDidEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
    registerCategory(ConfigStore.load())
    reconcile()
  }

  // MARK: - JS API

  @objc(configure:resolve:reject:)
  public func configure(_ config: [String: Any], resolve: @escaping Resolve, reject: @escaping Reject) {
    onMain {
      self.start()
      self.registerCategory(ConfigStore.save(config))
      resolve(nil)
    }
  }

  @objc(getPermissions:)
  public func getPermissions(_ resolve: @escaping Resolve) {
    center.getNotificationSettings { settings in
      DispatchQueue.main.async { resolve(Self.permissions(settings)) }
    }
  }

  @objc(requestNotificationPermission:resolve:)
  public func requestNotificationPermission(_ criticalAlerts: Bool, resolve: @escaping Resolve) {
    var options: UNAuthorizationOptions = [.alert, .sound, .badge]
    if criticalAlerts {
      // Only effective with the Apple-granted Critical Alerts entitlement.
      options.insert(.criticalAlert)
    }
    center.requestAuthorization(options: options) { _, error in
      if let error {
        NSLog("[CallReminder] Notification authorization failed: %@", error.localizedDescription)
      }
      self.center.getNotificationSettings { settings in
        DispatchQueue.main.async { resolve(Self.authorizationState(settings.authorizationStatus)) }
      }
    }
  }

  @objc(openNotificationSettings:)
  public func openNotificationSettings(_ resolve: @escaping Resolve) {
    onMain {
      if #available(iOS 16.0, *) {
        self.openURL(UIApplication.openNotificationSettingsURLString) { resolve(nil) }
      } else {
        self.openURL(UIApplication.openSettingsURLString) { resolve(nil) }
      }
    }
  }

  @objc(openAppSettings:)
  public func openAppSettings(_ resolve: @escaping Resolve) {
    onMain { self.openURL(UIApplication.openSettingsURLString) { resolve(nil) } }
  }

  @objc(showIncomingCall:resolve:reject:)
  public func showIncomingCall(_ options: [String: Any], resolve: @escaping Resolve, reject: @escaping Reject) {
    let call: IncomingCall
    do {
      call = try IncomingCall(json: options)
    } catch let IncomingCall.ParseError.missing(key) {
      reject("invalid_argument", "\(key) is required")
      return
    } catch {
      reject("invalid_argument", error.localizedDescription)
      return
    }
    onMain {
      self.start()
      self.show(call) { presentation, reason in
        var result: [String: Any] = ["presentation": presentation]
        result["reason"] = reason
        resolve(result)
      }
    }
  }

  @objc(endCall:resolve:)
  public func endCall(_ callId: String, resolve: @escaping Resolve) {
    onMain {
      self.start()
      self.end(callId, reason: Reason.api)
      resolve(nil)
    }
  }

  @objc(getActiveCall:)
  public func getActiveCall(_ resolve: @escaping Resolve) {
    onMain {
      self.start()
      let record = self.store.all().max { $0.createdAt < $1.createdAt }
      resolve(record.map { ["callId": $0.callId, "state": $0.state.rawValue] })
    }
  }

  @objc(speak:options:resolve:reject:)
  public func speak(_ text: String, options: [String: Any], resolve: @escaping Resolve, reject: @escaping Reject) {
    onMain {
      guard let language = options.nonEmptyString("language")?.replacingOccurrences(of: "_", with: "-"),
            !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
      else {
        reject("invalid_argument", "text and options.language are required")
        return
      }
      guard self.speakingCallId == nil else {
        reject("busy", "A reminder call is being spoken")
        return
      }
      // Standalone speech is only meaningful live: its events are never queued.
      let payload = options.nonEmptyString("utteranceId").map { ["utteranceId": $0] } ?? [:]
      var resolution = CallReminderSpeech.Resolution(language: language, usedFallback: false, text: text)
      let result = { ["language": resolution.language, "usedFallback": resolution.usedFallback] as [String: Any] }
      var listener = CallReminderSpeech.Listener()
      listener.onResolved = { resolution = $0 }
      listener.onStart = {
        self.emit(CallEvent(type: CallEvent.speechStarted, call: nil, payload: payload), durable: false)
      }
      listener.onDone = {
        self.emit(CallEvent(type: CallEvent.speechDone, call: nil, payload: payload), durable: false)
        resolve(result())
      }
      listener.onStopped = { resolve(result()) }
      listener.onError = { code in
        self.emit(CallEvent(type: CallEvent.speechError, call: nil, error: code, payload: payload), durable: false)
        reject("speech_failed", code)
      }
      self.speech.speak(
        CallReminderSpeech.Request(
          text: text,
          language: language,
          fallbackLanguage: options.nonEmptyString("fallbackLanguage")?.replacingOccurrences(of: "_", with: "-"),
          rate: Float(options.double("rate") ?? 1).clamped(0.25, 3),
          pitch: Float(options.double("pitch") ?? 1).clamped(0.5, 2),
          repeatCount: 1),
        listener: listener)
    }
  }

  @objc(stopSpeaking:)
  public func stopSpeaking(_ resolve: @escaping Resolve) {
    onMain {
      self.speech.stop()
      resolve(nil)
    }
  }

  @objc(isLanguageAvailable:resolve:)
  public func isLanguageAvailable(_ language: String, resolve: @escaping Resolve) {
    onMain { resolve(self.speech.availability(language)) }
  }

  @objc(getAvailableLanguages:)
  public func getAvailableLanguages(_ resolve: @escaping Resolve) {
    onMain { resolve(self.speech.languages()) }
  }

  @objc(getPendingEvents:)
  public func getPendingEvents(_ resolve: @escaping Resolve) {
    onMain {
      self.start()
      resolve(self.events.pending().map(\.dictionary))
    }
  }

  @objc(acknowledgeEvents:resolve:)
  public func acknowledgeEvents(_ ids: [String], resolve: @escaping Resolve) {
    onMain {
      self.events.acknowledge(ids)
      resolve(nil)
    }
  }

  // MARK: - Call state machine

  func show(_ call: IncomingCall, completion: @escaping (String, String?) -> Void) {
    let config = ConfigStore.load()
    if isDuplicate(call.callId, config: config) {
      completion(Presentation.suppressed, Reason.duplicate)
      return
    }
    center.getNotificationSettings { settings in
      DispatchQueue.main.async { self.post(call, config: config, settings: settings, completion: completion) }
    }
  }

  @discardableResult
  func answer(_ callId: String) -> Bool {
    guard var record = store.get(callId) else { return false }
    if record.state == .active {
      presentScreen(callId)
      return true
    }
    store.all()
      .filter { $0.callId != callId && $0.state == .active }
      .forEach { finish($0, type: CallEvent.ended, reason: Reason.replaced) }

    cancelTimeout(callId)
    // Silence the in-app ring before the reminder is spoken.
    ringer.stop(callId)
    center.removeDeliveredNotifications(withIdentifiers: [record.notificationId])
    record.state = .active
    record.answeredAt = Date()
    record.launchId = Self.launchId
    store.put(record)
    emit(CallEvent(type: CallEvent.answered, call: record.call, presentation: record.presentation))
    presentScreen(callId)
    screen(for: callId)?.render(record)
    startSpeech(record, repeatCount: record.call.repeatSpeech)
    return true
  }

  func decline(_ callId: String, reason: String?) {
    guard let record = store.get(callId) else { return }
    if record.state == .active {
      finish(record, type: CallEvent.ended, reason: reason ?? Reason.user)
    } else {
      finish(record, type: CallEvent.declined, reason: reason)
    }
  }

  func end(_ callId: String, reason: String) {
    guard let record = store.get(callId) else { return }
    finish(record, type: CallEvent.ended, reason: reason)
  }

  /// - Returns: the action, or nil when unknown / the call is not answered.
  @discardableResult
  func performAction(_ callId: String, actionId: String) -> CallAction? {
    guard let record = store.get(callId), record.state == .active,
          let action = record.call.resolvedActions(ConfigStore.load()).first(where: { $0.id == actionId })
    else { return nil }
    emit(CallEvent(type: CallEvent.action, call: record.call, actionId: action.id))
    if action.dismissesCall {
      finish(record, type: CallEvent.ended, reason: Reason.action)
    }
    return action
  }

  func replay(_ callId: String) {
    guard let record = store.get(callId), record.state == .active else { return }
    startSpeech(record, repeatCount: 1)
  }

  func speechStatus(_ callId: String) -> SpeechStatus {
    speechStatuses[callId] ?? .idle
  }

  /// The transcript of a call: its speakText, or the fallback wording when that is spoken.
  func transcript(for record: CallRecord) -> String {
    spokenTexts[record.callId] ?? record.call.speakText
  }

  // MARK: - Notification responses (via CallReminderNotifications)

  func isCallReminder(_ notification: UNNotification) -> Bool {
    let content = notification.request.content
    return content.userInfo[Self.userInfoCallKey] != nil || content.categoryIdentifier == ConfigStore.load().categoryId
  }

  func handle(_ response: UNNotificationResponse) {
    start()
    let notification = response.notification
    guard isAccepted(notification) else {
      // Not for this app's current user (`ios.requiredRemotePayload`).
      center.removeDeliveredNotifications(withIdentifiers: [notification.request.identifier])
      return
    }
    guard let call = call(from: notification) else { return }
    // Answer / Decline / a tap is the user's explicit choice and wins over a
    // reconciliation that ran first (it may already have reported the push as
    // a timeout: didBecomeActive and the delivered-notifications callback are
    // not ordered against this response). A plain dismissal is not.
    let explicit = response.actionIdentifier != UNNotificationDismissActionIdentifier
    let record =
      store.get(call.callId)
      ?? registerRemote(call, notification, presentation: Presentation.headsUp, reason: nil, force: explicit)
    guard let record else { return }

    switch response.actionIdentifier {
    case Self.declineActionId:
      decline(record.callId, reason: nil)
    case UNNotificationDismissActionIdentifier:
      if record.state == .ringing && Date() >= record.deadline.addingTimeInterval(-Self.timeoutSlack) {
        finish(record, type: CallEvent.timeout, reason: nil)
      } else if record.state == .ringing {
        finish(record, type: CallEvent.declined, reason: Reason.dismissed)
      }
    default:
      // Answer, or a tap on the notification itself.
      answer(record.callId)
    }
  }

  func presentationOptions(for notification: UNNotification) -> UNNotificationPresentationOptions {
    start()
    guard let call = call(from: notification) else { return [.banner, .list, .sound] }
    if notification.request.content.userInfo[Self.userInfoCallKey] != nil {
      // Our own local notification. With the call screen up it rings in-app
      // (posted silently), or with the notification sound when there is no
      // bundled ring to loop.
      guard store.get(call.callId) != nil else { return [] }
      if screen(for: call.callId) != nil {
        return ringer.isRinging(call.callId) ? [] : [.sound]
      }
      return [.banner, .list, .sound]
    }
    // A remote push while the app is open. Its local twin may already ring.
    guard isAccepted(notification) else { return [] }
    if store.get(call.callId) != nil || store.seenAt(call.callId) != nil {
      return []
    }
    let config = ConfigStore.load()
    guard let record = registerRemote(
      call,
      notification,
      presentation: config.presentCallScreen ? Presentation.fullScreen : Presentation.headsUp,
      reason: config.presentCallScreen ? Reason.appForeground : nil)
    else { return [] }
    scheduleTimeout(record)
    if config.presentCallScreen {
      presentScreen(record.callId)
      // The push's own sound could not be stopped on Answer: ring in-app.
      return ringInApp(record, config: config, critical: config.interruptionLevel == "critical") ? [] : [.sound]
    }
    return [.banner, .list, .sound]
  }

  /// Remote pushes must match `ios.requiredRemotePayload`; local calls always pass.
  private func isAccepted(_ notification: UNNotification) -> Bool {
    let userInfo = notification.request.content.userInfo
    guard userInfo[Self.userInfoCallKey] == nil else { return true }
    return ConfigStore.load().accepts(remote: Self.customValues(userInfo))
  }

  /// Loops the call's bundled ring sound in-app. False when there is none.
  private func ringInApp(_ record: CallRecord, config: CallReminderConfig, critical: Bool) -> Bool {
    guard let url = Self.bundledSoundURL(record.call.ringtone ?? config.sound) else { return false }
    return ringer.start(callId: record.callId, sound: url, critical: critical, volume: config.criticalVolume)
  }

  // MARK: - Posting

  private func post(
    _ call: IncomingCall,
    config: CallReminderConfig,
    settings: UNNotificationSettings,
    completion: @escaping (String, String?) -> Void
  ) {
    guard Self.isAuthorized(settings.authorizationStatus) else {
      completion(Presentation.suppressed, Reason.notificationsDisabled)
      return
    }
    // Another show() for the same id may have won while we read the settings.
    guard !isDuplicate(call.callId, config: config) else {
      completion(Presentation.suppressed, Reason.duplicate)
      return
    }
    // A newer reminder replaces one that is still ringing.
    store.all().filter { $0.state == .ringing }.forEach { finish($0, type: CallEvent.ended, reason: Reason.replaced) }

    let level = interruptionLevel(config, settings)
    let foreground = UIApplication.shared.applicationState == .active
    let inAppScreen = foreground && config.presentCallScreen
    // With the call screen up, ring in-app (stoppable on Answer) rather than
    // with the notification's sound — when there is a bundled ring to loop.
    let inAppRing = inAppScreen ? Self.bundledSoundURL(call.ringtone ?? config.sound) : nil
    let (presentation, reason) = Self.presentation(foreground: inAppScreen, level: level, settings: settings)
    let now = Date()
    let requestId = Self.requestPrefix + call.callId
    let record = CallRecord(
      call: call,
      state: .ringing,
      presentation: presentation,
      createdAt: now,
      deadline: now.addingTimeInterval(TimeInterval(call.timeoutSeconds)),
      notificationId: requestId,
      launchId: Self.launchId)
    store.put(record)
    store.markSeen(call.callId, at: now, retain: config.duplicateWindow)

    let request = UNNotificationRequest(
      identifier: requestId,
      content: content(for: call, config: config, level: level, silent: inAppRing != nil),
      trigger: nil)
    center.add(request) { error in
      DispatchQueue.main.async {
        if let error {
          NSLog("[CallReminder] Unable to post the call notification: %@", error.localizedDescription)
          self.store.remove(call.callId)
          completion(Presentation.suppressed, Reason.postFailed)
          return
        }
        guard self.store.get(call.callId)?.state == .ringing else {
          // Ended (e.g. endCall) while the request was being added.
          self.center.removeDeliveredNotifications(withIdentifiers: [requestId])
          completion(presentation, reason)
          return
        }
        self.scheduleTimeout(record)
        if inAppScreen {
          self.presentScreen(call.callId)
          if let inAppRing {
            self.ringer.start(
              callId: call.callId, sound: inAppRing, critical: level == .critical, volume: config.criticalVolume)
          }
        }
        self.emit(CallEvent(type: CallEvent.shown, call: call, presentation: presentation, reason: reason))
        completion(presentation, reason)
      }
    }
  }

  private func content(
    for call: IncomingCall, config: CallReminderConfig, level: UNNotificationInterruptionLevel, silent: Bool
  ) -> UNMutableNotificationContent {
    let content = UNMutableNotificationContent()
    content.title = call.title
    content.subtitle = call.callerName
    content.body = call.body
    content.categoryIdentifier = config.categoryId
    content.threadIdentifier = config.threadId
    content.interruptionLevel = level
    content.relevanceScore = 1
    content.sound =
      silent ? nil : Self.sound(call.ringtone ?? config.sound, critical: level == .critical, volume: config.criticalVolume)
    var userInfo: [String: Any] = call.payload
    userInfo[Self.userInfoCallKey] = call.json
    content.userInfo = userInfo
    return content
  }

  private func interruptionLevel(
    _ config: CallReminderConfig, _ settings: UNNotificationSettings
  ) -> UNNotificationInterruptionLevel {
    switch config.interruptionLevel {
    case "critical":
      // Needs the Critical Alerts entitlement and the user's consent.
      return settings.criticalAlertSetting == .enabled ? .critical : .timeSensitive
    case "active":
      return .active
    case "passive":
      return .passive
    default:
      return .timeSensitive
    }
  }

  private static func presentation(
    foreground: Bool,
    level: UNNotificationInterruptionLevel,
    settings: UNNotificationSettings
  ) -> (String, String?) {
    if foreground {
      return (Presentation.fullScreen, Reason.appForeground)
    }
    if settings.alertSetting != .enabled {
      return (Presentation.notification, Reason.alertsDisabled)
    }
    switch level {
    case .critical:
      return (Presentation.headsUp, Reason.critical)
    case .timeSensitive:
      return settings.timeSensitiveSetting == .disabled
        ? (Presentation.headsUp, Reason.timeSensitiveDisabled)
        : (Presentation.headsUp, Reason.timeSensitive)
    case .passive:
      return (Presentation.notification, Reason.passive)
    default:
      return (Presentation.headsUp, Reason.active)
    }
  }

  /// `silent`, `default`, or a sound file bundled in the app (or Library/Sounds).
  /// Unknown names fall back to the default sound.
  private static func sound(_ key: String, critical: Bool, volume: Float) -> UNNotificationSound? {
    if key == "silent" { return nil }
    if key != "default", let url = bundledSoundURL(key) {
      let soundName = UNNotificationSoundName(url.lastPathComponent)
      return critical
        ? UNNotificationSound.criticalSoundNamed(soundName, withAudioVolume: volume)
        : UNNotificationSound(named: soundName)
    }
    return critical ? UNNotificationSound.defaultCriticalSound(withAudioVolume: volume) : .default
  }

  /// A sound file in the app bundle or Library/Sounds (where notification
  /// sounds are looked up); nil for `default`/`silent` and unknown names.
  private static func bundledSoundURL(_ key: String) -> URL? {
    guard key != "default", key != "silent" else { return nil }
    let candidates =
      (key as NSString).pathExtension.isEmpty ? ["caf", "wav", "aiff", "aif"].map { "\(key).\($0)" } : [key]
    let librarySounds = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first?
      .appendingPathComponent("Sounds", isDirectory: true)
    for name in candidates {
      if let url = Bundle.main.url(forResource: name, withExtension: nil) { return url }
      if let url = librarySounds?.appendingPathComponent(name), FileManager.default.fileExists(atPath: url.path) {
        return url
      }
    }
    return nil
  }

  // MARK: - Lifecycle helpers

  private func finish(_ record: CallRecord, type: String, reason: String?) {
    let callId = record.callId
    store.remove(callId)
    cancelTimeout(callId)
    ringer.stop(callId)
    center.removeDeliveredNotifications(withIdentifiers: [record.notificationId])
    center.removePendingNotificationRequests(withIdentifiers: [record.notificationId])
    if speakingCallId == callId {
      speakingCallId = nil
      speechToken += 1
      speech.stop()
    }
    if pendingSpeech?.callId == callId {
      pendingSpeech = nil
    }
    if pendingScreenCallId == callId {
      pendingScreenCallId = nil
    }
    if record.state == .active {
      cancelIdle()
    }
    speechStatuses.removeValue(forKey: callId)
    spokenTexts.removeValue(forKey: callId)
    emit(CallEvent(type: type, call: record.call, reason: reason))
    screen(for: callId)?.render(nil)
  }

  /// Tracks a call that arrived as a remote push (we only learn about it when
  /// it is presented in the foreground or the user responds to it).
  /// - Parameter force: track it even if it was seen before (the user answered
  ///   or declined it after it had been reported); `shown` is only emitted once.
  private func registerRemote(
    _ call: IncomingCall, _ notification: UNNotification, presentation: String, reason: String?, force: Bool = false
  ) -> CallRecord? {
    let config = ConfigStore.load()
    let seen = store.seenAt(call.callId) != nil
    guard force || !seen else { return nil }
    store.all()
      .filter { $0.state == .ringing }
      .forEach { finish($0, type: CallEvent.ended, reason: Reason.replaced) }
    let record = CallRecord(
      call: call,
      state: .ringing,
      presentation: presentation,
      createdAt: notification.date,
      deadline: notification.date.addingTimeInterval(TimeInterval(call.timeoutSeconds)),
      notificationId: notification.request.identifier,
      launchId: Self.launchId)
    store.put(record)
    store.markSeen(call.callId, retain: config.duplicateWindow)
    if !seen {
      emit(CallEvent(type: CallEvent.shown, call: call, presentation: presentation, reason: reason))
    }
    return record
  }

  private func isDuplicate(_ callId: String, config: CallReminderConfig) -> Bool {
    if store.get(callId) != nil { return true }
    guard let seen = store.seenAt(callId) else { return false }
    return Date().timeIntervalSince(seen) < config.duplicateWindow
  }

  /// Reads the call from our own userInfo, or from a remote push's custom keys.
  func call(from notification: UNNotification) -> IncomingCall? {
    let content = notification.request.content
    if let json = content.userInfo[Self.userInfoCallKey] as? [String: Any] {
      return try? IncomingCall(json: json)
    }
    let config = ConfigStore.load()
    let keys = config.remoteKeys
    let values = Self.customValues(content.userInfo)
    func value(_ key: String) -> String? {
      values[key].flatMap { $0.isEmpty ? nil : $0 }
    }
    var json: [String: Any] = [
      "callId": value(keys.callId) ?? notification.request.identifier,
      "callerName": value(keys.callerName) ?? config.brandName,
      "title": value(keys.title) ?? content.title,
      "body": value(keys.body) ?? content.body,
      "speakText": value(keys.speakText) ?? content.body,
      "language": value(keys.language) ?? AVSpeechSynthesisVoice.currentLanguageCode(),
      // Without the call's wording: every queued event stores its payload on disk.
      "payload": config.eventPayload(fromRemote: values),
    ]
    json["fallbackLanguage"] = value(keys.fallbackLanguage)
    json["fallbackSpeakText"] = value(keys.fallbackSpeakText)
    json["speechRate"] = value(keys.speechRate)
    json["timeoutSeconds"] = value(keys.timeoutSeconds)
    json["actions"] = config.actions(forRemote: values)?.map(\.json)
    return try? IncomingCall(json: json)
  }

  /// A remote push's custom keys as strings (FCM sends every value as one).
  private static func customValues(_ userInfo: [AnyHashable: Any]) -> [String: String] {
    var values: [String: String] = [:]
    for (rawKey, value) in userInfo {
      guard let key = rawKey as? String, key != "aps", !key.hasPrefix("gcm."), !key.hasPrefix("google.") else {
        continue
      }
      if let string = value as? String {
        values[key] = string
      } else if let number = value as? NSNumber {
        values[key] = number.stringValue
      }
    }
    return values
  }

  private func registerCategory(_ config: CallReminderConfig) {
    let answer = UNNotificationAction(
      identifier: Self.answerActionId,
      title: config.actionTitle(.answer),
      options: [.foreground],
      icon: UNNotificationActionIcon(systemImageName: "phone.fill"))
    let decline = UNNotificationAction(
      identifier: Self.declineActionId,
      title: config.actionTitle(.decline),
      options: [.destructive],
      icon: UNNotificationActionIcon(systemImageName: "phone.down.fill"))
    let category = UNNotificationCategory(
      identifier: config.categoryId,
      actions: [answer, decline],
      intentIdentifiers: [],
      hiddenPreviewsBodyPlaceholder: config.label(.incomingTitle),
      options: [.customDismissAction])
    // setNotificationCategories replaces the whole set: merge with the app's own.
    center.getNotificationCategories { existing in
      var categories = existing.filter { $0.identifier != category.identifier }
      categories.insert(category)
      self.center.setNotificationCategories(categories)
    }
  }

  // MARK: - Speech

  private func startSpeech(_ record: CallRecord, repeatCount: Int) {
    let callId = record.callId
    guard UIApplication.shared.applicationState == .active else {
      pendingSpeech = (callId, repeatCount)
      setSpeechStatus(callId, .idle)
      return
    }
    pendingSpeech = nil
    let call = record.call
    let idleTimeout = ConfigStore.load().idleTimeout
    speechToken += 1
    let token = speechToken
    let isCurrent = { token == self.speechToken && self.speakingCallId == callId }
    let settle = { (status: SpeechStatus) in
      self.speakingCallId = nil
      self.setSpeechStatus(callId, status)
      self.scheduleIdle(callId, after: idleTimeout)
    }

    cancelIdle()
    speakingCallId = callId
    setSpeechStatus(callId, .speaking)
    var resolution: CallReminderSpeech.Resolution?
    var listener = CallReminderSpeech.Listener()
    listener.onResolved = { resolved in
      resolution = resolved
      guard isCurrent() else { return }
      if resolved.text != call.speakText {
        self.spokenTexts[callId] = resolved.text
      } else {
        self.spokenTexts.removeValue(forKey: callId)
      }
      self.screen(for: callId)?.showTranscript(resolved.text)
    }
    listener.onStart = {
      guard isCurrent() else { return }
      let fallback = resolution.flatMap { $0.usedFallback ? "\(Reason.fallbackLanguage):\($0.language)" : nil }
      self.emit(CallEvent(type: CallEvent.speechStarted, call: call, reason: fallback))
    }
    listener.onRange = { range in
      guard isCurrent() else { return }
      self.screen(for: callId)?.highlight(range)
    }
    listener.onDone = {
      guard isCurrent() else { return }
      settle(.done)
      self.emit(CallEvent(type: CallEvent.speechDone, call: call))
    }
    listener.onStopped = {
      guard isCurrent() else { return }
      settle(.idle)
    }
    listener.onError = { code in
      guard isCurrent() else { return }
      settle(.error)
      self.emit(CallEvent(type: CallEvent.speechError, call: call, error: code))
    }
    speech.speak(
      CallReminderSpeech.Request(
        text: call.speakText,
        language: call.language,
        fallbackLanguage: call.fallbackLanguage,
        rate: call.speechRate,
        pitch: call.pitch,
        repeatCount: repeatCount,
        fallbackText: call.fallbackSpeakText),
      listener: listener)
  }

  private func setSpeechStatus(_ callId: String, _ status: SpeechStatus) {
    speechStatuses[callId] = status
    screen(for: callId)?.showSpeech(status)
  }

  /// Ends an answered call that nobody interacts with after speech finished.
  private func scheduleIdle(_ callId: String, after delay: TimeInterval) {
    cancelIdle()
    let work = DispatchWorkItem {
      self.idleWork = nil
      if let record = self.store.get(callId), record.state == .active {
        self.finish(record, type: CallEvent.ended, reason: Reason.idle)
      }
    }
    idleWork = work
    DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
  }

  private func cancelIdle() {
    idleWork?.cancel()
    idleWork = nil
  }

  // MARK: - Timeouts & reconciliation

  /// In-process timer. While the app is suspended it cannot fire; the next
  /// activation reconciles overdue calls instead.
  private func scheduleTimeout(_ record: CallRecord) {
    let callId = record.callId
    cancelTimeout(callId)
    let work = DispatchWorkItem {
      self.timeouts.removeValue(forKey: callId)
      if let current = self.store.get(callId), current.state == .ringing {
        self.finish(current, type: CallEvent.timeout, reason: nil)
      }
    }
    timeouts[callId] = work
    DispatchQueue.main.asyncAfter(deadline: .now() + max(record.deadline.timeIntervalSinceNow, 0), execute: work)
  }

  private func cancelTimeout(_ callId: String) {
    timeouts.removeValue(forKey: callId)?.cancel()
  }

  private func reconcile() {
    let now = Date()
    for record in store.all() {
      switch record.state {
      case .ringing:
        if now >= record.deadline.addingTimeInterval(-Self.timeoutSlack) {
          finish(record, type: CallEvent.timeout, reason: nil)
        } else if timeouts[record.callId] == nil {
          scheduleTimeout(record)
        }
      case .active:
        // Speech and timers of an answered call died with its launch.
        if record.launchId != Self.launchId {
          finish(record, type: CallEvent.ended, reason: Reason.processDeath)
        }
      }
    }
    reconcileDeliveredRemoteCalls()
  }

  /// Remote call pushes the user never responded to: report `timeout` once
  /// they are overdue and clear them from Notification Center.
  private func reconcileDeliveredRemoteCalls() {
    center.getDeliveredNotifications { notifications in
      DispatchQueue.main.async {
        let config = ConfigStore.load()
        let now = Date()
        var expired: [String] = []
        for notification in notifications where self.isCallReminder(notification) {
          guard notification.request.content.userInfo[Self.userInfoCallKey] == nil else { continue }
          guard self.isAccepted(notification) else {
            // Meant for another account (`ios.requiredRemotePayload`): just clear it.
            expired.append(notification.request.identifier)
            continue
          }
          guard let call = self.call(from: notification),
                self.store.get(call.callId) == nil
          else { continue }
          let overdue = now.timeIntervalSince(notification.date) >= TimeInterval(call.timeoutSeconds)
          if self.store.seenAt(call.callId) != nil {
            // Already handled; only the stale banner is left.
            if overdue { expired.append(notification.request.identifier) }
            continue
          }
          if overdue {
            expired.append(notification.request.identifier)
            self.store.markSeen(call.callId, retain: config.duplicateWindow)
            self.emit(CallEvent(type: CallEvent.timeout, call: call, presentation: Presentation.headsUp))
          } else if let record = self.registerRemote(
            call, notification, presentation: Presentation.headsUp, reason: nil) {
            self.scheduleTimeout(record)
          }
        }
        if !expired.isEmpty {
          self.center.removeDeliveredNotifications(withIdentifiers: expired)
        }
      }
    }
  }

  @objc private func appDidBecomeActive() {
    reconcile()
    if let callId = pendingScreenCallId {
      presentScreen(callId)
    }
    if let callId = ringer.callId {
      if store.get(callId)?.state == .ringing {
        ringer.resume()
      } else {
        ringer.stop(callId)
      }
    }
    if let pending = pendingSpeech, let record = store.get(pending.callId), record.state == .active {
      startSpeech(record, repeatCount: pending.repeatCount)
    }
  }

  @objc private func appDidEnterBackground() {
    ringer.pause()
  }

  // MARK: - Call screen

  private func screen(for callId: String) -> CallScreenViewController? {
    guard let screen, screen.callId == callId else { return nil }
    return screen
  }

  private func presentScreen(_ callId: String) {
    let config = ConfigStore.load()
    guard config.presentCallScreen, let record = store.get(callId) else { return }
    if let screen, screen.presentingViewController != nil, !screen.isBeingDismissed {
      screen.bind(record, speech: speechStatus(callId))
      return
    }
    guard UIApplication.shared.applicationState == .active, let top = Self.topViewController() else {
      pendingScreenCallId = callId
      return
    }
    pendingScreenCallId = nil
    let controller = CallScreenViewController(core: self, config: config)
    controller.bind(record, speech: speechStatus(callId))
    screen = controller
    top.present(controller, animated: true)
  }

  private static func topViewController() -> UIViewController? {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    let windows =
      scenes.filter { $0.activationState == .foregroundActive }.flatMap(\.windows) + scenes.flatMap(\.windows)
    guard var top = (windows.first(where: { $0.isKeyWindow }) ?? windows.first)?.rootViewController else {
      return nil
    }
    while let presented = top.presentedViewController, !presented.isBeingDismissed {
      top = presented
    }
    return top
  }

  // MARK: - Utilities

  private func emit(_ event: CallEvent, durable: Bool = true) {
    if durable {
      events.append(event)
    }
    guard let sink, sink.callReminderCanEmit() else { return }
    sink.callReminderEmit(event.dictionary)
  }

  private func openURL(_ string: String, completion: @escaping () -> Void) {
    guard let url = URL(string: string) else {
      completion()
      return
    }
    UIApplication.shared.open(url, options: [:]) { _ in completion() }
  }

  private func onMain(_ work: @escaping () -> Void) {
    if Thread.isMainThread {
      work()
    } else {
      DispatchQueue.main.async(execute: work)
    }
  }

  static func isAuthorized(_ status: UNAuthorizationStatus) -> Bool {
    status == .authorized || status == .provisional || status == .ephemeral
  }

  static func authorizationState(_ status: UNAuthorizationStatus) -> String {
    switch status {
    case .authorized, .provisional, .ephemeral: return PermissionState.granted
    case .denied: return PermissionState.denied
    case .notDetermined: return PermissionState.notDetermined
    @unknown default: return PermissionState.unknown
    }
  }

  static func permissions(_ settings: UNNotificationSettings) -> [String: Any] {
    let notifications = authorizationState(settings.authorizationStatus)
    let undetermined = settings.authorizationStatus == .notDetermined
    func state(_ setting: UNNotificationSetting) -> String {
      if undetermined { return PermissionState.notDetermined }
      switch setting {
      case .enabled: return PermissionState.granted
      case .disabled: return PermissionState.denied
      case .notSupported: return PermissionState.notApplicable
      @unknown default: return PermissionState.unknown
      }
    }
    return [
      "platform": "ios",
      "osVersion": UIDevice.current.systemVersion,
      "manufacturer": "apple",
      "notifications": notifications,
      "channel": PermissionState.notApplicable,
      "fullScreenIntent": PermissionState.notApplicable,
      "exactAlarm": PermissionState.notApplicable,
      "batteryOptimization": PermissionState.notApplicable,
      "autoStart": PermissionState.notApplicable,
      "oemHasAutoStartManager": false,
      "timeSensitive": state(settings.timeSensitiveSetting),
      "criticalAlerts": state(settings.criticalAlertSetting),
      "lockScreen": state(settings.lockScreenSetting),
      "allRequiredGranted": notifications == PermissionState.granted,
    ]
  }
}
