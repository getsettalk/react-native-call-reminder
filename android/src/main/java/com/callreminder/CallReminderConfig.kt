package com.callreminder

import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Audio usage of the ringing notification (the channel sound). */
internal enum class RingUsage(val key: String, val usage: Int) {
  ALARM("alarm", AudioAttributes.USAGE_ALARM),
  RINGTONE("ringtone", AudioAttributes.USAGE_NOTIFICATION_RINGTONE),
  NOTIFICATION("notification", AudioAttributes.USAGE_NOTIFICATION);

  companion object {
    fun from(key: String?) = entries.firstOrNull { it.key == key } ?: ALARM
  }
}

/** Audio usage of the spoken reminder. `ALARM` stays audible with the ringer muted. */
internal enum class SpeechUsage(val key: String, val usage: Int) {
  ALARM("alarm", AudioAttributes.USAGE_ALARM),
  MEDIA("media", AudioAttributes.USAGE_MEDIA),
  ACCESSIBILITY("accessibility", AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY);

  companion object {
    fun from(key: String?) = entries.firstOrNull { it.key == key } ?: ALARM
  }
}

/** Keys of `configure({ labels })`, mapped to the default string resources. */
internal enum class Label(val key: String, val resId: Int) {
  ANSWER("answer", R.string.callreminder_label_answer),
  DECLINE("decline", R.string.callreminder_label_decline),
  INCOMING_TITLE("incomingTitle", R.string.callreminder_label_incoming_title),
  SPEAKING("speaking", R.string.callreminder_label_speaking),
  LISTENING("listening", R.string.callreminder_label_listening),
  ENDED("ended", R.string.callreminder_label_ended),
  END_CALL("endCall", R.string.callreminder_label_end_call),
  REPLAY("replay", R.string.callreminder_label_replay),
  TAP_TO_ANSWER("tapToAnswer", R.string.callreminder_label_tap_to_answer),
  SWIPE_TO_ANSWER("swipeToAnswer", R.string.callreminder_label_swipe_to_answer),
  SWIPE_TO_DECLINE("swipeToDecline", R.string.callreminder_label_swipe_to_decline),
}

/** `configure({ answerGesture })`: how the ringing call screen is answered/declined. */
internal enum class AnswerGesture(val key: String) {
  /** Drag the button upwards; a plain tap only nudges it (no pocket answers). */
  SWIPE("swipe"),
  TAP("tap");

  companion object {
    fun from(key: String?) = entries.firstOrNull { it.key == key } ?: SWIPE
  }
}

/**
 * `configure({ ringingStyle })`: the template of the ringing notification.
 * CALL uses Android's call-style template (Answer/Decline pills, top of the
 * shade) which, from Android 14, is the only kind of ongoing notification the
 * user cannot swipe away — so a ringing reminder can't be lost by an accidental
 * swipe or "Clear all". STANDARD keeps a plain notification with two actions.
 */
internal enum class RingingStyle(val key: String) {
  CALL("call"),
  STANDARD("standard");

  companion object {
    fun from(key: String?) = entries.firstOrNull { it.key == key } ?: CALL
  }
}

/**
 * Host-app configuration (`configure()` in JS). Persisted as the raw JSON the
 * app sent and parsed with defaults, so adding a field never breaks an older
 * stored config.
 */
internal class CallReminderConfig private constructor(private val json: JSONObject) {
  val channelId: String = json.optStringOrNull("channelId") ?: DEFAULT_CHANNEL_ID
  val ringtone: String = json.optStringOrNull("ringtone") ?: SoundResolver.DEFAULT
  val vibrationPattern: LongArray =
      json.optJSONArray("vibrationPattern")?.let { array ->
        LongArray(array.length()) { index -> array.optLong(index).coerceAtLeast(0) }
      } ?: DEFAULT_VIBRATION
  val appName: String? = json.optStringOrNull("appName")
  val smallIcon: String? = json.optStringOrNull("smallIcon")
  val largeIcon: String? = json.optStringOrNull("largeIcon")
  val defaultActions: List<CallAction> = CallAction.listFrom(json.optJSONArray("defaultActions"))
  val ringUsage: RingUsage = RingUsage.from(json.optStringOrNull("ringAudioUsage"))
  val speechUsage: SpeechUsage = SpeechUsage.from(json.optStringOrNull("speechAudioUsage"))
  val answerGesture: AnswerGesture = AnswerGesture.from(json.optStringOrNull("answerGesture"))
  val ringingStyle: RingingStyle = RingingStyle.from(json.optStringOrNull("ringingStyle"))
  val duplicateWindowMs: Long =
      json.optDouble("duplicateWindowSeconds", DEFAULT_DUPLICATE_WINDOW_S).toLong().coerceIn(0, 86_400) * 1000
  val idleTimeoutMs: Long =
      json.optDouble("idleTimeoutSeconds", DEFAULT_IDLE_TIMEOUT_S).toLong().coerceIn(5, 600) * 1000
  private val labels: Map<String, String> = json.optStringMap("labels")
  private val accent: String? = json.optStringOrNull("accentColor")
  private val background: String? = json.optStringOrNull("backgroundColor")
  private val text: String? = json.optStringOrNull("textColor")

  fun channelName(context: Context): String =
      json.optStringOrNull("channelName") ?: context.getString(R.string.callreminder_channel_name)

  fun channelDescription(context: Context): String =
      json.optStringOrNull("channelDescription")
          ?: context.getString(R.string.callreminder_channel_description)

  fun label(context: Context, label: Label): String =
      labels[label.key]?.takeIf { it.isNotBlank() } ?: context.getString(label.resId)

  fun accentColor(context: Context): Int = parseColor(accent, context, R.color.callreminder_accent)

  fun backgroundColor(context: Context): Int =
      parseColor(background, context, R.color.callreminder_background)

  fun textColor(context: Context): Int = parseColor(text, context, R.color.callreminder_text)

  /** Brand line: configured app name, else the host app's label. */
  fun brandName(context: Context): String =
      appName ?: context.applicationInfo.loadLabel(context.packageManager).toString()

  fun toJson(): JSONObject = JSONObject(json.toString())

  private fun parseColor(value: String?, context: Context, fallback: Int): Int =
      value?.let { runCatching { Color.parseColor(it) }.getOrNull() }
          ?: ContextCompat.getColor(context, fallback)

  companion object {
    const val DEFAULT_CHANNEL_ID = "call_reminder_incoming"
    private const val DEFAULT_DUPLICATE_WINDOW_S = 60.0
    private const val DEFAULT_IDLE_TIMEOUT_S = 60.0
    private val DEFAULT_VIBRATION = longArrayOf(0, 1000, 800, 1000, 800)

    fun fromJson(json: JSONObject?) = CallReminderConfig(json ?: JSONObject())
  }
}

/** Persists the config so receivers/activities in a cold process can read it. */
internal object ConfigStore {
  private const val PREFS = "com.callreminder.config"
  private const val KEY_CONFIG = "config"

  @Volatile private var cached: CallReminderConfig? = null

  fun load(context: Context): CallReminderConfig {
    cached?.let {
      return it
    }
    synchronized(this) {
      cached?.let {
        return it
      }
      val raw = prefs(context).getString(KEY_CONFIG, null)
      val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
      return CallReminderConfig.fromJson(json).also { cached = it }
    }
  }

  fun save(context: Context, json: JSONObject): CallReminderConfig {
    synchronized(this) {
      prefs(context).edit().putString(KEY_CONFIG, json.toString()).commit()
      return CallReminderConfig.fromJson(json).also { cached = it }
    }
  }

  private fun prefs(context: Context) =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
