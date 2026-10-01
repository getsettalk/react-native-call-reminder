package com.callreminder

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Routes call events to JS without ever losing one:
 * 1. every call event is appended to the durable [EventStore] first;
 * 2. while a React instance is running with a JS consumer attached (a listener
 *    or the background handler — the TurboModule is then "observing"), it gets
 *    the event immediately, whether the app is in the foreground or not;
 * 3. only when no React instance is active, and the app registered a background
 *    handler, is the headless task started (it may be refused in the
 *    background — the event stays queued);
 * 4. JS acknowledges handled events, removing them from the queue.
 */
internal object EventDispatcher {
  interface Sink {
    fun isObserving(): Boolean

    fun emit(event: CallEvent)
  }

  private const val TAG = "CallReminder"
  private const val PREFS = "com.callreminder.events"
  private const val KEY_HEADLESS = "headlessEnabled"
  private const val KEY_HEADLESS_INSTALL = "headlessInstallTime"

  @Volatile private var sink: WeakReference<Sink>? = null

  fun attach(target: Sink) {
    sink = WeakReference(target)
  }

  fun detach(target: Sink) {
    if (sink?.get() === target) {
      sink = null
    }
  }

  /**
   * @param durable false for events that only make sense live (speech progress
   *   of a standalone `speak()`), which are neither queued nor sent headless.
   */
  fun dispatch(context: Context, event: CallEvent, durable: Boolean = true) {
    if (durable) {
      EventStore.get(context).append(event)
    }
    val live = sink?.get()
    if (live != null && live.isObserving()) {
      try {
        live.emit(event)
        log(event, "live")
        return
      } catch (error: RuntimeException) {
        // The React instance can be tearing down between the check and the emit.
        Log.w(TAG, "Live delivery failed, falling back to the queue", error)
      }
    }
    if (durable && isHeadlessEnabled(context) && CallReminderHeadlessService.start(context, event)) {
      log(event, "headless")
    } else if (durable) {
      log(event, "queued")
    }
  }

  /** One line per call event (ids only, never the call's wording). */
  private fun log(event: CallEvent, route: String) {
    if (event.callId.isEmpty()) return
    val detail = listOfNotNull(event.actionId, event.presentation, event.reason, event.error).joinToString(" ")
    Log.i(TAG, "${event.type} ${event.callId} → $route${if (detail.isEmpty()) "" else " ($detail)"}")
  }

  /**
   * Remembered across process death so the headless task can run before JS
   * has loaded. Bound to the install/update time: a new app version must
   * re-register (it does, at bundle load) before we spin up JS for it.
   */
  fun setHeadlessEnabled(context: Context, enabled: Boolean) {
    prefs(context)
        .edit()
        .putBoolean(KEY_HEADLESS, enabled)
        .putLong(KEY_HEADLESS_INSTALL, lastUpdateTime(context))
        .apply()
  }

  private fun isHeadlessEnabled(context: Context): Boolean {
    val prefs = prefs(context)
    return prefs.getBoolean(KEY_HEADLESS, false) &&
        prefs.getLong(KEY_HEADLESS_INSTALL, -1) == lastUpdateTime(context)
  }

  private fun lastUpdateTime(context: Context): Long =
      try {
        val pm = context.packageManager
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
              @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
            }
        info.lastUpdateTime
      } catch (error: PackageManager.NameNotFoundException) {
        0L
      }

  private fun prefs(context: Context) =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  internal fun headlessIntent(context: Context, event: CallEvent): Intent =
      Intent(context, CallReminderHeadlessService::class.java).putExtras(event.toBundle())
}
