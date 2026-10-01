package com.callreminder

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Full-screen call UI. Opened by the notification's full-screen intent (device
 * locked/idle), by its Answer action or content tap, or directly while the
 * host app is in the foreground. Runs in its own task (see the manifest) and
 * holds no call state of its own: everything is read from [CallController], so
 * rotation and process recreation simply re-render the persisted call.
 *
 * A call with `lockScreenPrivacy: 'private'` shows only the app's brand while
 * a secure lock screen is up; answering it asks the user to unlock first
 * (Android 8+), and the details appear once the device is unlocked.
 */
class IncomingCallActivity : ComponentActivity() {
  private val main = Handler(Looper.getMainLooper())
  private val finishRunnable = Runnable { finishAndRemoveTask() }
  private lateinit var screen: CallScreenView
  private var callId: String? = null
  /** Waiting for the user to unlock before opening the host app; do not auto-close. */
  private var openingApp = false
  /** Waiting for the user to unlock before answering a private call. */
  private var unlockingToAnswer = false
  private val keyguard: KeyguardManager? by lazy { getSystemService(KeyguardManager::class.java) }

  /** The device was unlocked: reveal a private call's details. */
  private val userPresentReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    showOverLockScreen()
    val config = ConfigStore.load(this)
    val darkBackground = ColorUtils.calculateLuminance(config.backgroundColor(this)) < 0.5
    val barStyle =
        if (darkBackground) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    CallController.ensure(this)
    CallController.addObserver(observer)
    screen = CallScreenView(this, config, screenListener)
    setContentView(screen.root)
    onBackPressedDispatcher.addCallback(this, backCallback)
    ContextCompat.registerReceiver(
        this, userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)

    // After recreation the original action (e.g. ANSWER) has already been handled.
    val id = savedInstanceState?.getString(STATE_CALL_ID) ?: intent.getStringExtra(CallIntents.EXTRA_CALL_ID)
    handle(id, if (savedInstanceState == null) intent.action else null)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handle(intent.getStringExtra(CallIntents.EXTRA_CALL_ID), intent.action)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    callId?.let { outState.putString(STATE_CALL_ID, it) }
  }

  override fun onResume() {
    super.onResume()
    // The lock screen may have gone while we were paused (e.g. a trusted device).
    refresh()
  }

  override fun onDestroy() {
    unregisterReceiver(userPresentReceiver)
    CallController.removeObserver(observer)
    main.removeCallbacks(finishRunnable)
    screen.release()
    super.onDestroy()
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    val id = callId
    if (id != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
      // Like a phone: the volume keys silence a ringing call without answering it.
      if (CallController.record(this, id)?.state == CallState.RINGING) {
        CallController.silence(this, id)
        return true
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  private val observer =
      object : CallController.Observer {
        override fun onCallChanged(callId: String, record: CallRecord?) {
          if (callId == this@IncomingCallActivity.callId) render(record)
        }

        override fun onSpeechChanged(callId: String, status: CallController.SpeechStatus) {
          if (callId == this@IncomingCallActivity.callId) screen.showSpeech(status)
        }

        override fun onSpeechRange(callId: String, start: Int, end: Int) {
          if (callId == this@IncomingCallActivity.callId) screen.highlight(start, end)
        }

        override fun onSpeechText(callId: String, text: String) {
          if (callId == this@IncomingCallActivity.callId) screen.showTranscript(text)
        }
      }

  private val screenListener =
      object : CallScreenView.Listener {
        private val activity = this@IncomingCallActivity

        override fun onAnswer() {
          callId?.let { activity.answer(it) }
        }

        override fun onDecline() {
          callId?.let { CallController.decline(activity, it, null) }
        }

        override fun onEndCall() {
          callId?.let { CallController.end(activity, it, CallController.REASON_USER) }
        }

        override fun onReplay() {
          callId?.let { CallController.replay(activity, it) }
        }

        override fun onAction(actionId: String) {
          val id = callId ?: return
          val action = CallController.performAction(activity, id, actionId) ?: return
          if (action.opensApp) {
            openHostApp(id, action.id)
          }
        }
      }

  // ---------------------------------------------------------------------------

  private fun handle(id: String?, action: String?) {
    if (id == null) {
      finishAndRemoveTask()
      return
    }
    if (id != callId) {
      // A newer call took over this (single-instance) screen.
      callId = id
      main.removeCallbacks(finishRunnable)
    }
    val record = CallController.record(this, id)
    if (action == CallIntents.ACTION_ANSWER && record != null && record.state != CallState.ACTIVE) {
      // Renders through onCallChanged.
      if (answer(id)) return
    }
    render(record)
  }

  /**
   * Answers now, or, for a private call over a secure lock screen, once the
   * user has unlocked (the reminder is about to be read out and shown).
   * @return true when answered right away.
   */
  private fun answer(id: String): Boolean {
    val record = CallController.record(this, id) ?: return false
    val guard = keyguard
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && guard != null && isConcealed(record.call)) {
      if (!unlockingToAnswer) {
        unlockingToAnswer = true
        guard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
              override fun onDismissSucceeded() {
                unlockingToAnswer = false
                CallController.answer(this@IncomingCallActivity, id)
                refresh()
              }

              override fun onDismissCancelled() {
                unlockingToAnswer = false
              }

              override fun onDismissError() {
                unlockingToAnswer = false
              }
            })
      }
      return false
    }
    // Android 7.x has no keyguard dismissal API: answer, and keep the details
    // hidden until the user unlocks (ACTION_USER_PRESENT).
    return CallController.answer(this, id)
  }

  private fun isConcealed(call: IncomingCall): Boolean {
    val guard = keyguard ?: return false
    return call.privateOnLockScreen && guard.isKeyguardLocked && guard.isDeviceSecure
  }

  private fun refresh() {
    val id = callId ?: return
    CallController.record(this, id)?.let { render(it) }
  }

  private fun render(record: CallRecord?) {
    if (record == null) {
      volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
      screen.showEnded()
      if (!openingApp) {
        main.removeCallbacks(finishRunnable)
        main.postDelayed(finishRunnable, ENDED_LINGER_MS)
      }
      return
    }
    main.removeCallbacks(finishRunnable)
    screen.bind(record.call, CallController.transcript(record), isConcealed(record.call))
    // Once answered the volume keys adjust the stream the reminder is spoken on
    // (alarm by default); while ringing they silence the call (see onKeyDown).
    volumeControlStream =
        if (record.state == CallState.ACTIVE) CallAudio.legacyStreamFor(ConfigStore.load(this).speechUsage.usage)
        else AudioManager.USE_DEFAULT_STREAM_TYPE
    when (record.state) {
      CallState.RINGING,
      CallState.NOTIFIED -> screen.showRinging()
      CallState.ACTIVE ->
          screen.showActive(
              record,
              record.call.resolvedActions(ConfigStore.load(this)),
              CallController.speechStatus(record.callId))
    }
  }

  private val backCallback =
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          val id = callId
          val record = id?.let { CallController.record(this@IncomingCallActivity, it) }
          when (record?.state) {
            // Leave the call ringing quietly in the notification shade.
            CallState.RINGING,
            CallState.NOTIFIED -> {
              CallController.silence(this@IncomingCallActivity, record.callId)
              moveTaskToBack(true)
            }
            CallState.ACTIVE -> CallController.end(this@IncomingCallActivity, record.callId, CallController.REASON_USER)
            null -> finishAndRemoveTask()
          }
        }
      }

  /**
   * Brings the host app forward for an action that asked for it (e.g. "Open
   * app"). On a secure lock screen the user is asked to unlock first.
   */
  private fun openHostApp(callId: String, actionId: String) {
    val launch =
        packageManager.getLaunchIntentForPackage(packageName)?.apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          putExtra(CallIntents.EXTRA_CALL_ID, callId)
          putExtra(EXTRA_ACTION_ID, actionId)
        } ?: return
    fun start() {
      try {
        startActivity(launch)
      } catch (error: RuntimeException) {
        Log.w(TAG, "Unable to open the app", error)
      }
      finishAndRemoveTask()
    }
    val keyguard = getSystemService(KeyguardManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguard?.isKeyguardLocked == true) {
      // The action may already have ended the call and scheduled the auto-close.
      openingApp = true
      main.removeCallbacks(finishRunnable)
      keyguard.requestDismissKeyguard(
          this,
          object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = start()

            override fun onDismissCancelled() = finishAndRemoveTask()

            override fun onDismissError() = finishAndRemoveTask()
          })
    } else {
      start()
    }
  }

  @Suppress("DEPRECATION")
  private fun showOverLockScreen() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      window.addFlags(
          WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
  }

  companion object {
    /** Extra on the host app's launch intent when an action opened it. */
    const val EXTRA_ACTION_ID = "com.callreminder.extra.ACTION_ID"
    private const val STATE_CALL_ID = "callId"
    private const val ENDED_LINGER_MS = 1_200L
    private const val TAG = "CallReminder"
  }
}
