import UserNotifications

/**
 * Entry points for the host app's `UNUserNotificationCenterDelegate`.
 *
 * The library never replaces `UNUserNotificationCenter.delegate`: Notifee /
 * notify-kit and Firebase Messaging both install a delegate that forwards
 * notifications they do not own to the delegate they found at start-up. Make
 * your AppDelegate that original delegate (assign it in
 * `didFinishLaunchingWithOptions`, before React Native starts) and forward to
 * these methods — see the README for the full snippet.
 *
 * All methods are safe to call from any thread; work hops to the main thread.
 */
@objc(CallReminderNotifications)
public final class CallReminderNotifications: NSObject {
  /// Registers the call category (merged with the app's existing categories)
  /// and reconciles calls from earlier launches. Call it in
  /// `didFinishLaunchingWithOptions` so remote call pushes show Answer/Decline
  /// even before JS has configured the library.
  @objc public static func register() {
    onMain { CallReminderCore.shared.start() }
  }

  /// Whether the notification is a call reminder (local or remote).
  @objc(isCallReminder:)
  public static func isCallReminder(_ notification: UNNotification) -> Bool {
    CallReminderCore.shared.isCallReminder(notification)
  }

  /**
   * Handles Answer / Decline / tap / dismissal of a call reminder.
   * - Returns: false (and does not call `completionHandler`) when the response
   *   is not for a call reminder, so the caller can handle it.
   */
  @objc(handleResponse:completionHandler:)
  @discardableResult
  public static func handle(_ response: UNNotificationResponse, completionHandler: @escaping () -> Void) -> Bool {
    guard isCallReminder(response.notification) else { return false }
    onMain {
      CallReminderCore.shared.handle(response)
      completionHandler()
    }
    return true
  }

  /**
   * Foreground presentation of a call reminder: shows the in-app call screen
   * for remote call pushes and keeps the ring sound while it is up.
   * - Returns: false (and does not call `completionHandler`) for other notifications.
   */
  @objc(willPresentNotification:completionHandler:)
  @discardableResult
  public static func willPresent(
    _ notification: UNNotification,
    completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) -> Bool {
    guard isCallReminder(notification) else { return false }
    onMain { completionHandler(CallReminderCore.shared.presentationOptions(for: notification)) }
    return true
  }

  private static func onMain(_ work: @escaping () -> Void) {
    if Thread.isMainThread {
      work()
    } else {
      DispatchQueue.main.async(execute: work)
    }
  }
}
