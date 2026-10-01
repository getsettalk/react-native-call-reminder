import AVFoundation

/**
 * Wrapper around [AVSpeechSynthesizer]:
 * - resolves the voice with a fallback chain (requested → fallback → device
 *   language), matching on the base language when the exact region is missing,
 *   skipping novelty/character voices, and reports which one was used; when a
 *   fallback voice is used it speaks the request's fallback text instead;
 * - owns the audio session for the duration of speech (`.playback` +
 *   `.duckOthers`, so it is audible with the silent switch on) and restores the
 *   app's previous category afterwards;
 * - reports start / word ranges / completion and stops on interruptions.
 *
 * Main thread only; callbacks are delivered on the main thread.
 */
final class CallReminderSpeech: NSObject, AVSpeechSynthesizerDelegate {
  struct Request {
    let text: String
    let language: String
    let fallbackLanguage: String?
    let rate: Float
    let pitch: Float
    let repeatCount: Int
    /// Spoken instead of `text` when `language` has no voice (e.g. the same message in English).
    var fallbackText: String?
  }

  /// The language actually used and the text actually spoken.
  struct Resolution {
    let language: String
    let usedFallback: Bool
    let text: String
  }

  /// Callbacks of one `speak` call. Exactly one of done/stopped/error is called.
  struct Listener {
    var onResolved: (Resolution) -> Void = { _ in }
    var onStart: () -> Void = {}
    /// Character range within the original text currently being spoken.
    var onRange: (NSRange) -> Void = { _ in }
    var onDone: () -> Void = {}
    /// Stopped by `stop()` or replaced by a newer request.
    var onStopped: () -> Void = {}
    var onError: (String) -> Void = { _ in }
  }

  static let errorLanguage = "language_unavailable"
  static let errorAudioSession = "audio_session_unavailable"
  static let errorInterrupted = "interrupted"

  static let availabilityAvailable = "available"
  static let availabilityNotSupported = "not_supported"

  private final class Session {
    let listener: Listener
    let utterances: Set<ObjectIdentifier>
    let last: AVSpeechUtterance
    var started = false

    init(listener: Listener, utterances: [AVSpeechUtterance]) {
      self.listener = listener
      self.utterances = Set(utterances.map(ObjectIdentifier.init))
      last = utterances[utterances.count - 1]
    }

    func owns(_ utterance: AVSpeechUtterance) -> Bool {
      utterances.contains(ObjectIdentifier(utterance))
    }
  }

  private struct SavedAudio {
    let category: AVAudioSession.Category
    let mode: AVAudioSession.Mode
    let options: AVAudioSession.CategoryOptions
  }

  private static let repeatGap: TimeInterval = 0.9

  private let synthesizer = AVSpeechSynthesizer()
  private var session: Session?
  private var savedAudio: SavedAudio?

  var isSpeaking: Bool { session != nil }

  override init() {
    super.init()
    synthesizer.delegate = self
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(audioSessionInterrupted(_:)),
      name: AVAudioSession.interruptionNotification,
      object: AVAudioSession.sharedInstance())
  }

  func speak(_ request: Request, listener: Listener) {
    stopSession(notify: true)
    synthesizer.stopSpeaking(at: .immediate)

    guard let resolved = resolveVoice(request) else {
      listener.onError(Self.errorLanguage)
      return
    }
    let (voice, resolution) = resolved
    guard activateAudio() else {
      listener.onError(Self.errorAudioSession)
      return
    }

    let rate = (AVSpeechUtteranceDefaultSpeechRate * request.rate)
      .clamped(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate)
    let utterances = (0..<max(request.repeatCount, 1)).map { round -> AVSpeechUtterance in
      let utterance = AVSpeechUtterance(string: resolution.text)
      utterance.voice = voice
      utterance.rate = rate
      utterance.pitchMultiplier = request.pitch
      utterance.volume = 1
      utterance.preUtteranceDelay = round > 0 ? Self.repeatGap : 0
      return utterance
    }
    session = Session(listener: listener, utterances: utterances)
    listener.onResolved(resolution)
    for utterance in utterances {
      synthesizer.speak(utterance)
    }
  }

  func stop() {
    stopSession(notify: true)
    synthesizer.stopSpeaking(at: .immediate)
    deactivateAudio()
  }

  func availability(_ language: String) -> String {
    voice(for: language) != nil ? Self.availabilityAvailable : Self.availabilityNotSupported
  }

  func languages() -> [String] {
    Array(Set(AVSpeechSynthesisVoice.speechVoices().filter(Self.isSuitable).map(\.language))).sorted()
  }

  // MARK: - Voices

  private func resolveVoice(_ request: Request) -> (AVSpeechSynthesisVoice, Resolution)? {
    var candidates: [String] = []
    for tag in [request.language, request.fallbackLanguage, AVSpeechSynthesisVoice.currentLanguageCode()]
      .compactMap({ $0 }) where !candidates.contains(tag) {
      candidates.append(tag)
    }
    for (index, tag) in candidates.enumerated() {
      guard let voice = voice(for: tag) else { continue }
      let usedFallback = index > 0
      // Text in the requested language's script read by another language's
      // voice is unintelligible; the fallback wording is meant for this case.
      let fallbackText = request.fallbackText.flatMap {
        $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0
      }
      let text = usedFallback ? (fallbackText ?? request.text) : request.text
      return (voice, Resolution(language: voice.language, usedFallback: usedFallback, text: text))
    }
    return nil
  }

  /// Exact locale first, then any voice of the same base language. Within
  /// each: a downloaded enhanced/premium voice, else the system's default
  /// voice for the locale, else any remaining voice.
  private func voice(for tag: String) -> AVSpeechSynthesisVoice? {
    let normalized = tag.replacingOccurrences(of: "_", with: "-").lowercased()
    guard let base = normalized.split(separator: "-").first.map(String.init), !base.isEmpty else { return nil }
    let voices = AVSpeechSynthesisVoice.speechVoices().filter(Self.isSuitable)
    let exact = voices.filter { $0.language.lowercased() == normalized }
    let sameLanguage = voices.filter {
      let language = $0.language.lowercased()
      return language == base || language.hasPrefix(base + "-")
    }
    return Self.preferred(exact, locale: tag) ?? Self.preferred(sameLanguage, locale: base)
  }

  private static func preferred(_ voices: [AVSpeechSynthesisVoice], locale: String) -> AVSpeechSynthesisVoice? {
    guard !voices.isEmpty else { return nil }
    // Enhanced and premium voices exist only when the user downloaded them.
    if let best = voices.filter({ $0.quality != .default }).max(by: { $0.quality.rawValue < $1.quality.rawValue }) {
      return best
    }
    if let system = AVSpeechSynthesisVoice(language: locale),
       voices.contains(where: { $0.identifier == system.identifier }) {
      return system
    }
    return voices.first
  }

  /// Novelty and character voices (Albert, Bad News, Eloquence's Eddy,
  /// Grandma… on iOS 16/17+) and the user's Personal Voice share the
  /// standard voices' quality, so list order alone could pick one of them to
  /// read a medicine reminder.
  private static func isSuitable(_ voice: AVSpeechSynthesisVoice) -> Bool {
    let identifier = voice.identifier
    if identifier.hasPrefix("com.apple.eloquence.") || identifier.hasPrefix("com.apple.speech.synthesis.voice.") {
      return false
    }
    if #available(iOS 17.0, *) {
      if voice.voiceTraits.contains(.isNoveltyVoice) || voice.voiceTraits.contains(.isPersonalVoice) {
        return false
      }
    }
    return true
  }

  // MARK: - Audio session

  private func activateAudio() -> Bool {
    let audio = AVAudioSession.sharedInstance()
    if savedAudio == nil {
      savedAudio = SavedAudio(category: audio.category, mode: audio.mode, options: audio.categoryOptions)
    }
    do {
      try audio.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
      try audio.setActive(true)
      return true
    } catch {
      NSLog("[CallReminder] Unable to activate the audio session: %@", error.localizedDescription)
      deactivateAudio()
      return false
    }
  }

  private func deactivateAudio() {
    guard let saved = savedAudio else { return }
    savedAudio = nil
    let audio = AVAudioSession.sharedInstance()
    do {
      // Lets other apps' ducked audio return to full volume.
      try audio.setActive(false, options: .notifyOthersOnDeactivation)
    } catch {
      NSLog("[CallReminder] Unable to deactivate the audio session: %@", error.localizedDescription)
    }
    try? audio.setCategory(saved.category, mode: saved.mode, options: saved.options)
  }

  // MARK: - Sessions

  private func stopSession(notify: Bool) {
    guard let current = session else { return }
    session = nil
    if notify {
      current.listener.onStopped()
    }
  }

  @objc private func audioSessionInterrupted(_ notification: Notification) {
    guard let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
          AVAudioSession.InterruptionType(rawValue: raw) == .began
    else { return }
    DispatchQueue.main.async { [weak self] in
      guard let self, let current = self.session else { return }
      self.session = nil
      self.synthesizer.stopSpeaking(at: .immediate)
      self.deactivateAudio()
      current.listener.onError(Self.errorInterrupted)
    }
  }

  // MARK: - AVSpeechSynthesizerDelegate

  func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
    DispatchQueue.main.async { [weak self] in
      guard let current = self?.session, current.owns(utterance), !current.started else { return }
      current.started = true
      current.listener.onStart()
    }
  }

  func speechSynthesizer(
    _ synthesizer: AVSpeechSynthesizer,
    willSpeakRangeOfSpeechString characterRange: NSRange,
    utterance: AVSpeechUtterance
  ) {
    DispatchQueue.main.async { [weak self] in
      guard let current = self?.session, current.owns(utterance) else { return }
      current.listener.onRange(characterRange)
    }
  }

  func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
    DispatchQueue.main.async { [weak self] in
      guard let self, let current = self.session, current.last === utterance else { return }
      self.session = nil
      self.deactivateAudio()
      current.listener.onDone()
    }
  }

  func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
    // Our own stop() clears the session first, so this only fires for
    // cancellations we did not initiate.
    DispatchQueue.main.async { [weak self] in
      guard let self, let current = self.session, current.owns(utterance) else { return }
      self.session = nil
      self.deactivateAudio()
      current.listener.onStopped()
    }
  }
}
