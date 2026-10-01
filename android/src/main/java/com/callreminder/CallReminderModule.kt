package com.callreminder

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener

/**
 * TurboModule entry point. Thin by design: it converts bridge types, hops to
 * the main thread (where [CallController] and [SpeechEngine] live) and settles
 * promises. All behaviour lives in the focused helpers.
 */
@ReactModule(name = CallReminderModule.NAME)
class CallReminderModule(context: ReactApplicationContext) :
    NativeCallReminderSpec(context), LifecycleEventListener {

  /** A settings screen we opened; resolved with a fresh state once the user is back. */
  private class SettingsReturn(val promise: Promise, val check: () -> String) {
    var leftApp = false
  }

  @Volatile private var observing = false
  private var settingsReturn: SettingsReturn? = null
  private var standaloneAudio: CallAudio? = null

  /** Live delivery while JS listens; [EventDispatcher] only holds it weakly. */
  private val sink =
      object : EventDispatcher.Sink {
        override fun isObserving(): Boolean = observing && reactApplicationContext.hasActiveReactInstance()

        override fun emit(event: CallEvent) = emitOnEvent(event.toWritableMap())
      }

  init {
    context.addLifecycleEventListener(this)
    EventDispatcher.attach(sink)
    // Reconcile calls left behind by a previous process (timeouts, dead speech).
    UiThreadUtil.runOnUiThread { CallController.ensure(context) }
  }

  override fun getName(): String = NAME

  override fun invalidate() {
    EventDispatcher.detach(sink)
    reactApplicationContext.removeLifecycleEventListener(this)
    observing = false
    UiThreadUtil.runOnUiThread { settleSettingsReturn() }
    super.invalidate()
  }

  // --- Configuration & permissions --------------------------------------------

  override fun configure(config: ReadableMap, promise: Promise) {
    try {
      val saved = ConfigStore.save(reactApplicationContext, config.toJson())
      CallNotifications.ensureChannel(reactApplicationContext, saved)
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject(E_CONFIGURE, error.message, error)
    }
  }

  override fun getPermissions(promise: Promise) {
    promise.resolve(PermissionsHelper.snapshot(reactApplicationContext, reactApplicationContext.currentActivity))
  }

  override fun requestNotificationPermission(criticalAlerts: Boolean, promise: Promise) {
    val context = reactApplicationContext
    val current = PermissionsHelper.notifications(context, context.currentActivity)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
      // Nothing to prompt for: either granted or switched off in Settings by the user.
      promise.resolve(current)
      return
    }
    val activity = context.currentActivity as? PermissionAwareActivity
    if (activity == null) {
      promise.reject(E_NO_ACTIVITY, "A foreground activity is required to request the notification permission")
      return
    }
    UiThreadUtil.runOnUiThread {
      PermissionsHelper.markNotificationsAsked(context)
      activity.requestPermissions(
          arrayOf(Manifest.permission.POST_NOTIFICATIONS),
          REQUEST_NOTIFICATIONS,
          PermissionListener { requestCode, _, _ ->
            if (requestCode != REQUEST_NOTIFICATIONS) return@PermissionListener false
            promise.resolve(PermissionsHelper.notifications(context, context.currentActivity))
            true
          })
    }
  }

  override fun requestFullScreenIntentPermission(promise: Promise) {
    val context = reactApplicationContext
    val current = PermissionsHelper.fullScreenIntent(context)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || current == PermissionState.GRANTED) {
      promise.resolve(current)
      return
    }
    openSettingsAndWait(PermissionsHelper.fullScreenIntentSettings(context), promise) {
      PermissionsHelper.fullScreenIntent(context)
    }
  }

  override fun openExactAlarmSettings(promise: Promise) {
    val context = reactApplicationContext
    // Granted, or nothing to grant (below Android 12 / not declared by the app).
    val current = PermissionsHelper.exactAlarm(context)
    if (current == PermissionState.GRANTED || current == PermissionState.NOT_APPLICABLE) {
      promise.resolve(current)
      return
    }
    openSettingsAndWait(PermissionsHelper.exactAlarmSettings(context), promise) {
      PermissionsHelper.exactAlarm(context)
    }
  }

  override fun openBatteryOptimizationSettings(promise: Promise) {
    val context = reactApplicationContext
    // Nothing left to allow when already unrestricted. An optimized app (also
    // `granted`) still gets the page, so the user can pick "Unrestricted".
    if (PermissionsHelper.batteryUsage(context) == BatteryUsage.UNRESTRICTED) {
      promise.resolve(PermissionsHelper.batteryOptimization(context))
      return
    }
    openSettingsAndWait(PermissionsHelper.batteryOptimizationSettings(context), promise) {
      PermissionsHelper.batteryOptimization(context)
    }
  }

  override fun openAutoStartSettings(promise: Promise) {
    UiThreadUtil.runOnUiThread {
      promise.resolve(OemAutoStart.open(reactApplicationContext) { intent -> launch(intent) })
    }
  }

  override fun openNotificationSettings(channelId: String?, promise: Promise) {
    val context = reactApplicationContext
    val config = ConfigStore.load(context)
    // Never creates the channel (see PermissionsHelper.channel): before
    // configure() this falls back to the app's notification settings.
    UiThreadUtil.runOnUiThread {
      launchFirst(PermissionsHelper.notificationSettings(context, channelId ?: config.channelId))
      promise.resolve(null)
    }
  }

  override fun openAppSettings(promise: Promise) {
    UiThreadUtil.runOnUiThread {
      launch(PermissionsHelper.appDetails(reactApplicationContext))
      promise.resolve(null)
    }
  }

  // --- Calls --------------------------------------------------------------------

  override fun showIncomingCall(options: ReadableMap, promise: Promise) {
    val call =
        try {
          IncomingCall.fromJson(options.toJson())
        } catch (error: IllegalArgumentException) {
          promise.reject(E_INVALID_ARGUMENT, error.message, error)
          return
        }
    UiThreadUtil.runOnUiThread {
      try {
        val result = CallController.show(reactApplicationContext, call)
        promise.resolve(
            Arguments.createMap().apply {
              putString("presentation", result.presentation)
              result.reason?.let { putString("reason", it) }
            })
      } catch (error: Exception) {
        Log.e(TAG, "showIncomingCall failed", error)
        promise.reject(E_SHOW, error.message, error)
      }
    }
  }

  override fun endCall(callId: String, promise: Promise) {
    UiThreadUtil.runOnUiThread {
      CallController.end(reactApplicationContext, callId, CallController.REASON_API)
      promise.resolve(null)
    }
  }

  override fun getActiveCall(promise: Promise) {
    UiThreadUtil.runOnUiThread {
      val record = CallController.activeCall(reactApplicationContext)
      promise.resolve(
          record?.let {
            Arguments.createMap().apply {
              putString("callId", it.callId)
              putString("state", if (it.state == CallState.ACTIVE) "active" else "ringing")
            }
          })
    }
  }

  // --- Speech -------------------------------------------------------------------

  override fun speak(text: String, options: ReadableMap, promise: Promise) {
    val json = options.toJson()
    val language = json.optStringOrNull("language")?.replace('_', '-')
    if (language == null || text.isBlank()) {
      promise.reject(E_INVALID_ARGUMENT, "text and options.language are required")
      return
    }
    val utteranceId = json.optStringOrNull("utteranceId")
    val payload = utteranceId?.let { mapOf(PAYLOAD_UTTERANCE_ID to it) }.orEmpty()
    val request =
        SpeechEngine.Request(
            text = text,
            language = language,
            fallbackLanguage = json.optStringOrNull("fallbackLanguage")?.replace('_', '-'),
            rate = json.optDouble("rate", 1.0).toFloat().coerceIn(0.25f, 3f),
            pitch = json.optDouble("pitch", 1.0).toFloat().coerceIn(0.5f, 2f),
            repeat = 1,
            usage = ConfigStore.load(reactApplicationContext).speechUsage.usage,
        )

    UiThreadUtil.runOnUiThread {
      val context = reactApplicationContext
      if (CallController.isCallSpeaking()) {
        promise.reject(E_BUSY, "A reminder call is being spoken")
        return@runOnUiThread
      }
      val audio = standaloneAudio ?: CallAudio(context).also { standaloneAudio = it }
      fun emit(type: String, error: String? = null) {
        // Standalone speech is only meaningful live: never queued or run headless.
        EventDispatcher.dispatch(
            context, CallEvent.create(type, null, error = error, payload = payload), durable = false)
      }

      audio.requestSpeechFocus(request.usage) { SpeechEngine.get(context).stop() }
      SpeechEngine.get(context)
          .speak(
              request,
              object : SpeechEngine.Listener {
                private var resolution = SpeechEngine.Resolution(language, usedFallback = false, text = text)

                private fun result() =
                    Arguments.createMap().apply {
                      putString("language", resolution.language)
                      putBoolean("usedFallback", resolution.usedFallback)
                    }

                override fun onResolved(resolution: SpeechEngine.Resolution) {
                  this.resolution = resolution
                }

                override fun onStart() = emit(CallEvent.SPEECH_STARTED)

                override fun onDone() {
                  audio.abandonFocus()
                  emit(CallEvent.SPEECH_DONE)
                  promise.resolve(result())
                }

                override fun onStopped() {
                  audio.abandonFocus()
                  promise.resolve(result())
                }

                override fun onError(code: String) {
                  audio.abandonFocus()
                  emit(CallEvent.SPEECH_ERROR, code)
                  promise.reject(E_SPEECH, code)
                }
              })
    }
  }

  override fun stopSpeaking(promise: Promise) {
    UiThreadUtil.runOnUiThread {
      SpeechEngine.get(reactApplicationContext).stop()
      promise.resolve(null)
    }
  }

  override fun isLanguageAvailable(language: String, promise: Promise) {
    UiThreadUtil.runOnUiThread {
      SpeechEngine.get(reactApplicationContext).availability(language.replace('_', '-')) { promise.resolve(it) }
    }
  }

  override fun getAvailableLanguages(promise: Promise) {
    UiThreadUtil.runOnUiThread {
      SpeechEngine.get(reactApplicationContext).languages { languages ->
        promise.resolve(Arguments.createArray().apply { languages.forEach { pushString(it) } })
      }
    }
  }

  // --- Events -------------------------------------------------------------------

  override fun getPendingEvents(promise: Promise) {
    val events = EventStore.get(reactApplicationContext).pending()
    promise.resolve(Arguments.createArray().apply { events.forEach { pushMap(it.toWritableMap()) } })
  }

  override fun acknowledgeEvents(ids: ReadableArray, promise: Promise) {
    val list = (0 until ids.size()).mapNotNull { ids.getString(it) }
    EventStore.get(reactApplicationContext).acknowledge(list)
    promise.resolve(null)
  }

  override fun setObserving(observing: Boolean) {
    this.observing = observing
  }

  override fun setBackgroundHandlerEnabled(enabled: Boolean) {
    EventDispatcher.setHeadlessEnabled(reactApplicationContext, enabled)
  }

  // --- Settings round-trips -----------------------------------------------------

  override fun onHostResume() {
    val pending = settingsReturn ?: return
    if (pending.leftApp) {
      settleSettingsReturn()
    }
  }

  override fun onHostPause() {
    settingsReturn?.leftApp = true
  }

  override fun onHostDestroy() {
    settleSettingsReturn()
  }

  /**
   * Opens the first settings screen that resolves and resolves [promise] with
   * [check] once the user comes back to the app. Without a foreground activity
   * the screen is opened in a new task and the promise resolves immediately.
   */
  private fun openSettingsAndWait(intents: List<Intent>, promise: Promise, check: () -> String) {
    UiThreadUtil.runOnUiThread {
      // Only one round-trip at a time; an older one resolves with what we know now.
      settleSettingsReturn()
      val activity = reactApplicationContext.currentActivity
      if (activity == null) {
        launchFirst(intents)
        promise.resolve(check())
        return@runOnUiThread
      }
      val pending = SettingsReturn(promise, check)
      settingsReturn = pending
      if (!launchFirst(intents, activity)) {
        settleSettingsReturn()
      }
    }
  }

  private fun settleSettingsReturn() {
    val pending = settingsReturn ?: return
    settingsReturn = null
    pending.promise.resolve(pending.check())
  }

  private fun launchFirst(intents: List<Intent>, activity: Activity? = reactApplicationContext.currentActivity): Boolean =
      intents.any { launch(it, activity) }

  private fun launch(intent: Intent, activity: Activity? = reactApplicationContext.currentActivity): Boolean {
    val context: Context = activity ?: reactApplicationContext
    if (activity == null) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
      context.startActivity(intent)
      true
    } catch (error: ActivityNotFoundException) {
      false
    } catch (error: SecurityException) {
      // Some OEM screens are exported but guarded by a signature permission.
      Log.i(TAG, "Settings screen refused: ${intent.component ?: intent.action}")
      false
    }
  }

  companion object {
    const val NAME = NativeCallReminderSpec.NAME
    private const val TAG = "CallReminder"
    private const val REQUEST_NOTIFICATIONS = 0x4352 // "CR"
    private const val PAYLOAD_UTTERANCE_ID = "utteranceId"

    private const val E_INVALID_ARGUMENT = "invalid_argument"
    private const val E_NO_ACTIVITY = "no_activity"
    private const val E_CONFIGURE = "configure_failed"
    private const val E_SHOW = "show_failed"
    private const val E_BUSY = "busy"
    private const val E_SPEECH = "speech_failed"
  }
}
