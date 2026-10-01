package com.callreminder

import android.content.Context
import android.content.Intent
import android.util.Log
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig

/**
 * Runs the JS background handler (`registerBackgroundHandler`) for one event.
 * A plain started service, never a foreground service: Android only lets us
 * start it while the app is allowed to (foreground, or briefly after a
 * notification action / allow-while-idle alarm). When it is refused the event
 * simply stays in the durable queue for `getPendingEvents()`.
 */
class CallReminderHeadlessService : HeadlessJsTaskService() {
  override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? {
    val extras = intent?.extras ?: return null
    return HeadlessJsTaskConfig(TASK_NAME, Arguments.fromBundle(extras), TASK_TIMEOUT_MS, true)
  }

  companion object {
    /** Must match `HEADLESS_TASK_NAME` in the JS package. */
    const val TASK_NAME = "CallReminderBackgroundEvent"
    private const val TASK_TIMEOUT_MS = 60_000L
    private const val TAG = "CallReminder"

    internal fun start(context: Context, event: CallEvent): Boolean =
        try {
          context.startService(EventDispatcher.headlessIntent(context, event)) != null
        } catch (error: IllegalStateException) {
          // Background start not allowed right now; the event remains queued.
          Log.i(TAG, "Headless start deferred: ${error.message}")
          false
        } catch (error: SecurityException) {
          Log.w(TAG, "Headless start refused", error)
          false
        }
  }
}
