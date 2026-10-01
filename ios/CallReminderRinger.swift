import AVFoundation

/**
 * Rings a call in-app while its call screen is up in the foreground.
 *
 * A notification's sound cannot be stopped once iOS starts playing it, so a
 * ring played that way would keep going over the spoken reminder after Answer
 * (and after Decline). While the app is active, calls are therefore presented
 * without a notification sound and rung here instead, where Answer, Decline
 * and the timeout can stop it at once. Locked or in the background, the
 * notification's own sound rings as usual.
 *
 * The ring respects the Ring/Silent switch like a notification sound does
 * (`.ambient`), except for calls at the Critical Alert level (`.playback`).
 * Main thread only.
 */
final class CallReminderRinger {
  private struct SavedAudio {
    let category: AVAudioSession.Category
    let mode: AVAudioSession.Mode
    let options: AVAudioSession.CategoryOptions
  }

  private var player: AVAudioPlayer?
  private var savedAudio: SavedAudio?
  private var critical = false
  /// The call being rung, also while paused in the background.
  private(set) var callId: String?

  func isRinging(_ callId: String) -> Bool {
    self.callId == callId
  }

  /// - Returns: false (and rings nothing) when the sound file cannot be played.
  @discardableResult
  func start(callId: String, sound url: URL, critical: Bool, volume: Float) -> Bool {
    stop()
    let player: AVAudioPlayer
    do {
      player = try AVAudioPlayer(contentsOf: url)
    } catch {
      NSLog("[CallReminder] Unable to load the ring sound: %@", error.localizedDescription)
      return false
    }
    player.numberOfLoops = -1
    player.volume = critical ? volume : 1
    self.player = player
    self.critical = critical
    self.callId = callId
    guard activateAudio(), player.play() else {
      stop()
      return false
    }
    return true
  }

  /// Stops ringing `callId` (any call when nil).
  func stop(_ callId: String? = nil) {
    if let callId, callId != self.callId { return }
    player?.stop()
    player = nil
    self.callId = nil
    deactivateAudio()
  }

  /// The app left the foreground: iOS would suspend the player mid-ring anyway.
  func pause() {
    guard let player, player.isPlaying else { return }
    player.pause()
    deactivateAudio()
  }

  /// Back in the foreground with the call still ringing.
  func resume() {
    guard let player, !player.isPlaying else { return }
    if !(activateAudio() && player.play()) {
      stop()
    }
  }

  private func activateAudio() -> Bool {
    let audio = AVAudioSession.sharedInstance()
    if savedAudio == nil {
      savedAudio = SavedAudio(category: audio.category, mode: audio.mode, options: audio.categoryOptions)
    }
    do {
      if critical {
        try audio.setCategory(.playback, mode: .default, options: [.duckOthers])
      } else {
        // Ambient always mixes; ducking is not available for it.
        try audio.setCategory(.ambient, mode: .default, options: [])
      }
      try audio.setActive(true)
      return true
    } catch {
      NSLog("[CallReminder] Unable to activate the audio session for ringing: %@", error.localizedDescription)
      return false
    }
  }

  private func deactivateAudio() {
    guard let saved = savedAudio else { return }
    savedAudio = nil
    let audio = AVAudioSession.sharedInstance()
    do {
      try audio.setActive(false, options: .notifyOthersOnDeactivation)
    } catch {
      NSLog("[CallReminder] Unable to deactivate the audio session: %@", error.localizedDescription)
    }
    try? audio.setCategory(saved.category, mode: saved.mode, options: saved.options)
  }
}
