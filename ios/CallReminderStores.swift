import Foundation

enum CallState: String {
  /// Notification posted, waiting for Answer/Decline/timeout.
  case ringing
  /// Answered: call screen open, reminder being / has been spoken.
  case active
}

struct CallRecord {
  var call: IncomingCall
  var state: CallState
  var presentation: String
  var createdAt: Date
  var deadline: Date
  var answeredAt: Date?
  /// Identifier of the notification request showing this call (local or remote).
  var notificationId: String
  /// Launch that owns the in-memory side (speech, timers) of an active call.
  var launchId: String

  var callId: String { call.callId }

  var json: [String: Any] {
    var out: [String: Any] = [
      "call": call.json,
      "state": state.rawValue,
      "presentation": presentation,
      "createdAt": createdAt.timeIntervalSince1970,
      "deadline": deadline.timeIntervalSince1970,
      "notificationId": notificationId,
      "launchId": launchId,
    ]
    out["answeredAt"] = answeredAt?.timeIntervalSince1970
    return out
  }

  init(
    call: IncomingCall, state: CallState, presentation: String, createdAt: Date, deadline: Date,
    notificationId: String, launchId: String
  ) {
    self.call = call
    self.state = state
    self.presentation = presentation
    self.createdAt = createdAt
    self.deadline = deadline
    self.notificationId = notificationId
    self.launchId = launchId
  }

  init?(json: [String: Any]) {
    guard let callJson = json["call"] as? [String: Any],
          let call = try? IncomingCall(json: callJson),
          let state = (json["state"] as? String).flatMap(CallState.init(rawValue:)),
          let notificationId = json.nonEmptyString("notificationId")
    else { return nil }
    self.call = call
    self.state = state
    presentation = (json["presentation"] as? String) ?? Presentation.notification
    createdAt = Date(timeIntervalSince1970: json.double("createdAt") ?? 0)
    deadline = Date(timeIntervalSince1970: json.double("deadline") ?? 0)
    answeredAt = json.double("answeredAt").map(Date.init(timeIntervalSince1970:))
    self.notificationId = notificationId
    launchId = (json["launchId"] as? String) ?? ""
  }
}

/**
 * Durable state of live calls plus a "recently seen" index used to drop
 * duplicate deliveries (the same push arriving twice, or a local call and its
 * remote twin). Main thread only.
 */
final class CallStore {
  private static let callsKey = "com.callreminder.calls"
  private static let recentKey = "com.callreminder.recent"
  private static let minRetain: TimeInterval = 60 * 60

  private let defaults = UserDefaults.standard

  func all() -> [CallRecord] {
    calls().values.compactMap { ($0 as? [String: Any]).flatMap(CallRecord.init(json:)) }
  }

  func get(_ callId: String) -> CallRecord? {
    (calls()[callId] as? [String: Any]).flatMap(CallRecord.init(json:))
  }

  func put(_ record: CallRecord) {
    var json = calls()
    json[record.callId] = record.json
    write(json, Self.callsKey)
  }

  func remove(_ callId: String) {
    var json = calls()
    if json.removeValue(forKey: callId) != nil {
      write(json, Self.callsKey)
    }
  }

  /// Remembers that a call was presented, so later deliveries of the same id are duplicates.
  func markSeen(_ callId: String, at date: Date = Date(), retain: TimeInterval) {
    let cutoff = date.timeIntervalSince1970 - max(retain, Self.minRetain)
    var json = (read(Self.recentKey) as? [String: Double] ?? [:]).filter { $0.value >= cutoff }
    json[callId] = date.timeIntervalSince1970
    write(json, Self.recentKey)
  }

  func seenAt(_ callId: String) -> Date? {
    (read(Self.recentKey) as? [String: Double])?[callId].map(Date.init(timeIntervalSince1970:))
  }

  private func calls() -> [String: Any] {
    read(Self.callsKey) as? [String: Any] ?? [:]
  }

  private func read(_ key: String) -> Any? {
    JSON.object(defaults.data(forKey: key))
  }

  private func write(_ object: Any, _ key: String) {
    if let data = JSON.data(object) {
      defaults.set(data, forKey: key)
    }
  }
}

/**
 * Durable FIFO of events not yet acknowledged by JS, written atomically to
 * Application Support (excluded from backups: events are device-local).
 * Bounded so a host that never acknowledges cannot grow it without limit.
 */
final class EventStore {
  private static let maxEvents = 500
  private let queue = DispatchQueue(label: "com.callreminder.events")
  private let url: URL?

  init() {
    let fileManager = FileManager.default
    guard let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
      url = nil
      return
    }
    var directory = support.appendingPathComponent("callreminder", isDirectory: true)
    try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
    var values = URLResourceValues()
    values.isExcludedFromBackup = true
    try? directory.setResourceValues(values)
    url = directory.appendingPathComponent("events.json")
  }

  func append(_ event: CallEvent) {
    queue.sync {
      var events = readAll()
      events.append(event)
      write(Array(events.suffix(Self.maxEvents)))
    }
  }

  func pending() -> [CallEvent] {
    queue.sync { readAll() }
  }

  func acknowledge(_ ids: [String]) {
    guard !ids.isEmpty else { return }
    let idSet = Set(ids)
    queue.sync {
      let events = readAll()
      let remaining = events.filter { !idSet.contains($0.id) }
      if remaining.count != events.count {
        write(remaining)
      }
    }
  }

  private func readAll() -> [CallEvent] {
    guard let url, let data = try? Data(contentsOf: url) else { return [] }
    guard let array = JSON.object(data) as? [[String: Any]] else {
      NSLog("[CallReminder] Discarding unreadable event queue")
      return []
    }
    return array.compactMap(CallEvent.init(dictionary:))
  }

  private func write(_ events: [CallEvent]) {
    guard let url, let data = JSON.data(events.map(\.dictionary)) else { return }
    do {
      try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    } catch {
      NSLog("[CallReminder] Unable to persist event queue: %@", error.localizedDescription)
    }
  }
}
