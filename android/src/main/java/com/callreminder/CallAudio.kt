package com.callreminder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Audio-side checks and focus handling for the spoken reminder. Main thread only. */
internal class CallAudio(context: Context) {
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val main = Handler(Looper.getMainLooper())
  private var focusRequest: AudioFocusRequest? = null
  private var legacyListener: AudioManager.OnAudioFocusChangeListener? = null

  /**
   * True while a telephony/VoIP call is ringing or in progress. Uses the audio
   * mode, which needs no READ_PHONE_STATE permission and also covers VoIP apps.
   */
  fun isUserBusyWithCall(): Boolean {
    val mode = audioManager.mode
    if (mode == AudioManager.MODE_IN_CALL ||
        mode == AudioManager.MODE_IN_COMMUNICATION ||
        mode == AudioManager.MODE_RINGTONE) {
      return true
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING) {
      return true
    }
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        (mode == AudioManager.MODE_CALL_REDIRECT || mode == AudioManager.MODE_COMMUNICATION_REDIRECT)
  }

  /**
   * Requests transient focus that ducks other audio. [onLost] runs on the main
   * thread when focus is lost for good or transiently (e.g. a phone call), not
   * when we are merely asked to duck.
   */
  fun requestSpeechFocus(usage: Int, onLost: () -> Unit): Boolean {
    abandonFocus()
    val listener =
        AudioManager.OnAudioFocusChangeListener { change ->
          if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            onLost()
          }
        }
    val result =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          val request =
              AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                  .setAudioAttributes(
                      AudioAttributes.Builder()
                          .setUsage(usage)
                          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                          .build())
                  .setOnAudioFocusChangeListener(listener, main)
                  .setWillPauseWhenDucked(false)
                  .build()
          focusRequest = request
          audioManager.requestAudioFocus(request)
        } else {
          legacyListener = listener
          @Suppress("DEPRECATION")
          audioManager.requestAudioFocus(
              listener, legacyStreamFor(usage), AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  fun abandonFocus() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
      focusRequest = null
    } else {
      legacyListener?.let {
        @Suppress("DEPRECATION") audioManager.abandonAudioFocus(it)
      }
      legacyListener = null
    }
  }

  companion object {
    /**
     * Stream equivalent of an AudioAttributes usage: for pre-O APIs that take a
     * stream, and for the call screen's volume keys.
     */
    fun legacyStreamFor(usage: Int): Int =
        when {
          usage == AudioAttributes.USAGE_ALARM -> AudioManager.STREAM_ALARM
          usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> AudioManager.STREAM_RING
          usage == AudioAttributes.USAGE_NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
          usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY &&
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> AudioManager.STREAM_ACCESSIBILITY
          else -> AudioManager.STREAM_MUSIC
        }
  }
}
