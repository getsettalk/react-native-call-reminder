package com.callreminder

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log

/**
 * The call state machine. Every transition is persisted in [CallStore] before
 * side effects run, so receivers, the call screen and the JS module can drive
 * the same call from any process lifetime.
 *
 * ```
 *            show()
 *   ┌──────────┴──────────┐
 * RINGING             NOTIFIED (user busy: quiet notification)
 *   │ answer()            │ answer()
 *   ├────────► ACTIVE ◄───┘
 *   │            │ action / end / idle
 *   │            ▼
 *   │          ended
 *   ├─ decline()/dismissed ─► declined
 *   └─ deadline ─────────────► timeout   (RINGING and NOTIFIED alike)
 * ```
 *
 * Main thread only. Public entry points assert it.
 */
internal object CallController {
  interface Observer {
    /** The call changed state or was removed ([record] null). */
    fun onCallChanged(callId: String, record: CallRecord?)

    fun onSpeechChanged(callId: String, status: SpeechStatus) {}

    fun onSpeechRange(callId: String, start: Int, end: Int) {}

    /** The text being spoken changed (the call's fallback wording replaced it). */
    fun onSpeechText(callId: String, text: String) {}
  }

  enum class SpeechStatus {
    IDLE,
    SPEAKING,
    DONE,
    ERROR,
  }

  data class ShowResult(val presentation: String, val reason: String?)

  const val PRESENTATION_FULL_SCREEN = "full_screen"
  const val PRESENTATION_HEADS_UP = "heads_up"
  const val PRESENTATION_NOTIFICATION = "notification"
  const val PRESENTATION_SUPPRESSED = "suppressed"

  const val REASON_APP_FOREGROUND = "app_foreground"
  const val REASON_DEVICE_IN_USE = "device_in_use"
  const val REASON_FSI_DENIED = "full_screen_intent_denied"
  const val REASON_IN_PHONE_CALL = "in_phone_call"
  const val REASON_BUSY = "busy"
  const val REASON_DUPLICATE = "duplicate"
  const val REASON_NOTIFICATIONS_DISABLED = "notifications_disabled"
  const val REASON_CHANNEL_DISABLED = "channel_disabled"
  const val REASON_CHANNEL_LOW = "channel_importance_low"
  const val REASON_DISMISSED = "dismissed"
  const val REASON_REPLACED = "replaced"
  const val REASON_API = "api"
  const val REASON_USER = "user"
  const val REASON_ACTION = "action"
  const val REASON_IDLE = "idle"
  const val REASON_PROCESS_DEATH = "process_death"
  const val REASON_FALLBACK_LANGUAGE = "fallback_language"

  private const val TAG = "CallReminder"
  /** A dismissal this close to the deadline is the system's timeout, not the user. */
  private const val TIMEOUT_SLACK_MS = 2_000L

  const val ERROR_SPEECH_TIMEOUT = "speech_timeout"
  /** Generous per-character allowance at rate 1.0 (slow scripts, slow engines). */
  private const val SPEECH_MS_PER_CHAR = 110.0
  private const val SPEECH_REPEAT_GAP_MS = 900L
  /** Engine start-up, audio routing and the like. */
  private const val SPEECH_MARGIN_MS = 20_000L
  private const val SPEECH_WATCHDOG_MIN_MS = 30_000L
  private const val SPEECH_WATCHDOG_MAX_MS = 3 * 60 * 1000L

  private val main = Handler(Looper.getMainLooper())
  private lateinit var app: Context
  private lateinit var store: CallStore
  private lateinit var audio: CallAudio
  private var recovered = false
  private val observers = LinkedHashSet<Observer>()
  private val timeoutRunnables = HashMap<String, Runnable>()
  private val speechStatus = HashMap<String, SpeechStatus>()
  /** What is actually spoken when it differs from the call's speakText (fallback wording). */
  private val spokenText = HashMap<String, String>()
  private var idleRunnable: Runnable? = null
  private var speechWatchdog: Runnable? = null
  private var speakingCallId: String? = null
  private var speechToken = 0

  /** Idempotent; also reconciles state left behind by a previous process. */
  fun ensure(context: Context) {
    assertMain()
    if (!::app.isInitialized) {
      app = context.applicationContext
      store = CallStore.get(app)
      audio = CallAudio(app)
    }
    if (!recovered) {
      recovered = true
      recover()
    }
  }

  fun addObserver(observer: Observer) {
    assertMain()
    observers += observer
  }

  fun removeObserver(observer: Observer) {
    assertMain()
    observers -= observer
  }

  fun record(context: Context, callId: String): CallRecord? {
    ensure(context)
    return store.get(callId)
  }

  /** The ringing or answered call, if any (quiet NOTIFIED calls are not "active"). */
  fun activeCall(context: Context): CallRecord? {
    ensure(context)
    return store.all().filter { it.state != CallState.NOTIFIED }.maxByOrNull { it.createdAt }
  }

  fun speechStatus(callId: String): SpeechStatus = speechStatus[callId] ?: SpeechStatus.IDLE

  /** The transcript of an answered call: its speakText, or the fallback wording when that is spoken. */
  fun transcript(record: CallRecord): String = spokenText[record.callId] ?: record.call.speakText

  /** True while an answered call is being spoken (standalone `speak()` must wait). */
  fun isCallSpeaking(): Boolean = speakingCallId != null

  fun show(context: Context, call: IncomingCall): ShowResult {
    ensure(context)
    val config = ConfigStore.load(app)
    val now = System.currentTimeMillis()

    val existing = store.get(call.callId)
    val lastShown = store.presentedAt(call.callId)
    if (existing != null || (lastShown != null && now - lastShown < config.duplicateWindowMs)) {
      return ShowResult(PRESENTATION_SUPPRESSED, REASON_DUPLICATE)
    }
    if (!CallNotifications.canPost(app)) {
      return ShowResult(PRESENTATION_SUPPRESSED, REASON_NOTIFICATIONS_DISABLED)
    }
    val channelId = CallNotifications.ensureChannel(app, config, call.ringtone)
    val importance = CallNotifications.channelImportance(app, channelId)
    if (importance == android.app.NotificationManager.IMPORTANCE_NONE) {
      return ShowResult(PRESENTATION_SUPPRESSED, REASON_CHANNEL_DISABLED)
    }

    val others = store.all()
    val busyReason =
        when {
          audio.isUserBusyWithCall() -> REASON_IN_PHONE_CALL
          others.any { it.state == CallState.ACTIVE } -> REASON_BUSY
          else -> null
        }
    val deadline = now + call.timeoutSeconds * 1000L

    if (busyReason != null) {
      val record =
          CallRecord(call, CallState.NOTIFIED, PRESENTATION_NOTIFICATION, now, deadline, pid = Process.myPid())
      store.put(record)
      store.markPresented(call.callId, now, config.duplicateWindowMs)
      if (!CallNotifications.postQuiet(app, config, record)) {
        store.remove(call.callId)
        return ShowResult(PRESENTATION_SUPPRESSED, REASON_NOTIFICATIONS_DISABLED)
      }
      // Expires like a ringing call: a reminder answered hours later would be stale.
      scheduleTimeout(record)
      emit(CallEvent.create(CallEvent.SHOWN, call, presentation = PRESENTATION_NOTIFICATION, reason = busyReason))
      notifyChanged(record)
      return ShowResult(PRESENTATION_NOTIFICATION, busyReason)
    }

    // A newer reminder replaces one that is still ringing.
    others.filter { it.state == CallState.RINGING }.forEach { finish(it, CallEvent.ENDED, REASON_REPLACED) }

    val fullScreenAllowed = PermissionsHelper.canUseFullScreenIntent(app)
    val foreground = isAppInForeground()
    val (presentation, reason) =
        when {
          importance != null && importance < android.app.NotificationManager.IMPORTANCE_HIGH ->
              PRESENTATION_NOTIFICATION to REASON_CHANNEL_LOW
          foreground -> PRESENTATION_FULL_SCREEN to REASON_APP_FOREGROUND
          !fullScreenAllowed -> PRESENTATION_HEADS_UP to REASON_FSI_DENIED
          isDeviceInUse() -> PRESENTATION_HEADS_UP to REASON_DEVICE_IN_USE
          else -> PRESENTATION_FULL_SCREEN to null
        }

    val record = CallRecord(call, CallState.RINGING, presentation, now, deadline, pid = Process.myPid())
    store.put(record)
    store.markPresented(call.callId, now, config.duplicateWindowMs)
    if (!CallNotifications.postRinging(app, config, record)) {
      store.remove(call.callId)
      return ShowResult(PRESENTATION_SUPPRESSED, REASON_NOTIFICATIONS_DISABLED)
    }
    scheduleTimeout(record)
    if (foreground) {
      // Visible apps may start activities; no need to rely on the system's FSI heuristics.
      startScreen(call.callId, CallIntents.ACTION_SHOW)
    }
    emit(CallEvent.create(CallEvent.SHOWN, call, presentation = presentation, reason = reason))
    notifyChanged(record)
    return ShowResult(presentation, reason)
  }

  /** @return false when the call no longer exists. */
  fun answer(context: Context, callId: String): Boolean {
    ensure(context)
    val record = store.get(callId) ?: return false
    if (record.state == CallState.ACTIVE) return true

    store.all()
        .filter { it.callId != callId && it.state == CallState.ACTIVE }
        .forEach { finish(it, CallEvent.ENDED, REASON_REPLACED) }

    cancelTimeout(callId)
    CallNotifications.cancel(app, callId)
    val active = record.copy(state = CallState.ACTIVE, answeredAt = System.currentTimeMillis(), pid = Process.myPid())
    store.put(active)
    emit(CallEvent.create(CallEvent.ANSWERED, record.call, presentation = record.presentation))
    notifyChanged(active)
    startSpeech(active, record.call.repeatSpeech)
    return true
  }

  fun decline(context: Context, callId: String, reason: String?) {
    ensure(context)
    val record = store.get(callId) ?: return
    if (record.state == CallState.ACTIVE) {
      finish(record, CallEvent.ENDED, reason ?: REASON_USER)
    } else {
      finish(record, CallEvent.DECLINED, reason)
    }
  }

  /** The notification was swiped away (or removed by the system at its timeout). */
  fun onDismissed(context: Context, callId: String) {
    ensure(context)
    val record = store.get(callId) ?: return
    when (record.state) {
      CallState.RINGING,
      CallState.NOTIFIED ->
          if (System.currentTimeMillis() >= record.deadlineAt - TIMEOUT_SLACK_MS) {
            finish(record, CallEvent.TIMEOUT, null)
          } else {
            finish(record, CallEvent.DECLINED, REASON_DISMISSED)
          }
      CallState.ACTIVE -> Unit
    }
  }

  fun onTimeout(context: Context, callId: String) {
    ensure(context)
    val record = store.get(callId) ?: return
    if (record.state == CallState.ACTIVE) return
    if (System.currentTimeMillis() < record.deadlineAt - TIMEOUT_SLACK_MS) {
      // Early wake-up (e.g. a stale alarm from an older call with the same id).
      scheduleTimeout(record)
      return
    }
    finish(record, CallEvent.TIMEOUT, null)
  }

  fun end(context: Context, callId: String, reason: String) {
    ensure(context)
    store.get(callId)?.let { finish(it, CallEvent.ENDED, reason) }
  }

  /** @return the action, or null when unknown / the call is not answered. */
  fun performAction(context: Context, callId: String, actionId: String): CallAction? {
    ensure(context)
    val record = store.get(callId) ?: return null
    if (record.state != CallState.ACTIVE) return null
    val action = record.call.resolvedActions(ConfigStore.load(app)).firstOrNull { it.id == actionId } ?: return null
    emit(CallEvent.create(CallEvent.ACTION, record.call, actionId = action.id))
    if (action.dismissesCall) {
      finish(record, CallEvent.ENDED, REASON_ACTION)
    }
    return action
  }

  fun replay(context: Context, callId: String) {
    ensure(context)
    val record = store.get(callId) ?: return
    if (record.state == CallState.ACTIVE) {
      startSpeech(record, repeat = 1)
    }
  }

  /** Volume key while ringing: stop the sound, keep the call. */
  fun silence(context: Context, callId: String) {
    ensure(context)
    val record = store.get(callId) ?: return
    if (record.state != CallState.RINGING || record.silenced) return
    val silenced = record.copy(silenced = true)
    store.put(silenced)
    CallNotifications.postRinging(app, ConfigStore.load(app), silenced)
  }

  // ---------------------------------------------------------------------------

  private fun finish(record: CallRecord, type: String, reason: String?) {
    val callId = record.callId
    store.remove(callId)
    cancelTimeout(callId)
    CallNotifications.cancel(app, callId)
    if (speakingCallId == callId) {
      speakingCallId = null
      speechToken++
      cancelSpeechWatchdog()
      SpeechEngine.get(app).stop()
      audio.abandonFocus()
    }
    if (record.state == CallState.ACTIVE) {
      cancelIdle()
    }
    speechStatus.remove(callId)
    spokenText.remove(callId)
    emit(CallEvent.create(type, record.call, reason = reason))
    observers.toList().forEach { it.onCallChanged(callId, null) }
  }

  private fun startSpeech(record: CallRecord, repeat: Int) {
    val call = record.call
    val callId = call.callId
    val config = ConfigStore.load(app)
    // Each run gets a token so callbacks of a replaced run (e.g. Replay pressed
    // mid-sentence) can never touch the state of the current one.
    val token = ++speechToken
    fun isCurrent() = token == speechToken && speakingCallId == callId
    fun settle(status: SpeechStatus) {
      speakingCallId = null
      cancelSpeechWatchdog()
      audio.abandonFocus()
      setSpeechStatus(callId, status)
    }

    cancelIdle()
    speakingCallId = callId
    setSpeechStatus(callId, SpeechStatus.SPEAKING)
    // The engine reports nothing at all when its service dies or never
    // initialises. Without a bound, the call would stay "speaking" forever and
    // every later reminder would be posted as busy.
    cancelSpeechWatchdog()
    val watchdog = Runnable {
      speechWatchdog = null
      if (isCurrent()) {
        Log.w(TAG, "Speech of $callId did not finish in time; giving up")
        speechToken++
        SpeechEngine.get(app).stop()
        settle(SpeechStatus.ERROR)
        emit(CallEvent.create(CallEvent.SPEECH_ERROR, call, error = ERROR_SPEECH_TIMEOUT))
        scheduleIdle(callId, config.idleTimeoutMs)
      }
    }
    speechWatchdog = watchdog
    main.postDelayed(watchdog, speechWatchdogMs(call, repeat))
    audio.requestSpeechFocus(config.speechUsage.usage) {
      if (isCurrent()) {
        speechToken++
        SpeechEngine.get(app).stop()
        settle(SpeechStatus.ERROR)
        emit(CallEvent.create(CallEvent.SPEECH_ERROR, call, error = SpeechEngine.ERROR_INTERRUPTED))
        scheduleIdle(callId, config.idleTimeoutMs)
      }
    }
    SpeechEngine.get(app)
        .speak(
            SpeechEngine.Request(
                text = call.speakText,
                language = call.language,
                fallbackLanguage = call.fallbackLanguage,
                rate = call.speechRate,
                pitch = call.pitch,
                repeat = repeat,
                usage = config.speechUsage.usage,
                fallbackText = call.fallbackSpeakText,
            ),
            object : SpeechEngine.Listener {
              private var resolution: SpeechEngine.Resolution? = null

              override fun onResolved(resolution: SpeechEngine.Resolution) {
                this.resolution = resolution
                if (!isCurrent()) return
                if (resolution.text != call.speakText) {
                  spokenText[callId] = resolution.text
                } else {
                  spokenText.remove(callId)
                }
                observers.toList().forEach { it.onSpeechText(callId, resolution.text) }
              }

              override fun onStart() {
                if (!isCurrent()) return
                val fallback = resolution?.takeIf { it.usedFallback }
                emit(
                    CallEvent.create(
                        CallEvent.SPEECH_STARTED,
                        call,
                        reason = fallback?.let { "$REASON_FALLBACK_LANGUAGE:${it.language}" }))
              }

              override fun onRange(start: Int, end: Int) {
                if (!isCurrent()) return
                observers.toList().forEach { it.onSpeechRange(callId, start, end) }
              }

              override fun onDone() {
                if (!isCurrent()) return
                settle(SpeechStatus.DONE)
                emit(CallEvent.create(CallEvent.SPEECH_DONE, call))
                scheduleIdle(callId, config.idleTimeoutMs)
              }

              override fun onStopped() {
                if (isCurrent()) {
                  settle(SpeechStatus.IDLE)
                  scheduleIdle(callId, config.idleTimeoutMs)
                }
              }

              override fun onError(code: String) {
                if (!isCurrent()) return
                settle(SpeechStatus.ERROR)
                emit(CallEvent.create(CallEvent.SPEECH_ERROR, call, error = code))
                scheduleIdle(callId, config.idleTimeoutMs)
              }
            })
  }

  private fun setSpeechStatus(callId: String, status: SpeechStatus) {
    speechStatus[callId] = status
    observers.toList().forEach { it.onSpeechChanged(callId, status) }
  }

  private fun scheduleIdle(callId: String, delayMs: Long) {
    cancelIdle()
    val runnable = Runnable {
      idleRunnable = null
      store.get(callId)?.takeIf { it.state == CallState.ACTIVE }?.let { finish(it, CallEvent.ENDED, REASON_IDLE) }
    }
    idleRunnable = runnable
    main.postDelayed(runnable, delayMs)
  }

  private fun cancelIdle() {
    idleRunnable?.let { main.removeCallbacks(it) }
    idleRunnable = null
  }

  private fun cancelSpeechWatchdog() {
    speechWatchdog?.let { main.removeCallbacks(it) }
    speechWatchdog = null
  }

  /** An upper bound for speaking [call] [repeat] times, including engine start-up. */
  private fun speechWatchdogMs(call: IncomingCall, repeat: Int): Long {
    val longest = maxOf(call.speakText.length, call.fallbackSpeakText?.length ?: 0)
    val perRound = longest * SPEECH_MS_PER_CHAR / call.speechRate
    val estimate = (perRound * repeat).toLong() + SPEECH_REPEAT_GAP_MS * (repeat - 1) + SPEECH_MARGIN_MS
    return estimate.coerceIn(SPEECH_WATCHDOG_MIN_MS, SPEECH_WATCHDOG_MAX_MS)
  }

  /**
   * In-process timer for punctuality plus an AlarmManager backup that fires
   * even if the process was killed (allow-while-idle; exact when permitted).
   */
  private fun scheduleTimeout(record: CallRecord) {
    val callId = record.callId
    timeoutRunnables.remove(callId)?.let { main.removeCallbacks(it) }
    val delay = (record.deadlineAt - System.currentTimeMillis()).coerceAtLeast(0)
    val runnable = Runnable {
      timeoutRunnables.remove(callId)
      onTimeout(app, callId)
    }
    timeoutRunnables[callId] = runnable
    main.postDelayed(runnable, delay)

    val alarms = app.getSystemService(AlarmManager::class.java) ?: return
    val operation = CallIntents.timeout(app, callId)
    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.deadlineAt, operation)
      } else {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.deadlineAt, operation)
      }
    } catch (error: SecurityException) {
      alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.deadlineAt, operation)
    }
  }

  private fun cancelTimeout(callId: String) {
    timeoutRunnables.remove(callId)?.let { main.removeCallbacks(it) }
    app.getSystemService(AlarmManager::class.java)?.cancel(CallIntents.timeout(app, callId))
  }

  /** Reconciles persisted calls after a (re)start of the process. */
  private fun recover() {
    val now = System.currentTimeMillis()
    val pid = Process.myPid()
    store.all().forEach { record ->
      when (record.state) {
        CallState.RINGING,
        CallState.NOTIFIED ->
            if (now >= record.deadlineAt - TIMEOUT_SLACK_MS) finish(record, CallEvent.TIMEOUT, null)
            else scheduleTimeout(record)
        // Speech and timers of an answered call died with its process.
        CallState.ACTIVE -> if (record.pid != pid) finish(record, CallEvent.ENDED, REASON_PROCESS_DEATH)
      }
    }
  }

  private fun startScreen(callId: String, action: String) {
    try {
      app.startActivity(CallIntents.screenIntent(app, callId, action))
    } catch (error: RuntimeException) {
      Log.w(TAG, "Unable to open the call screen", error)
    }
  }

  private fun isAppInForeground(): Boolean {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
        info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
  }

  /** Screen on and unlocked: the system turns a full-screen intent into a heads-up. */
  private fun isDeviceInUse(): Boolean {
    val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
    return power.isInteractive && !keyguard.isKeyguardLocked
  }

  private fun notifyChanged(record: CallRecord) {
    observers.toList().forEach { it.onCallChanged(record.callId, record) }
  }

  private fun emit(event: CallEvent) {
    EventDispatcher.dispatch(app, event)
  }

  private fun assertMain() {
    check(Looper.myLooper() == Looper.getMainLooper()) { "CallController must be used on the main thread" }
  }
}
