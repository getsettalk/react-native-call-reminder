package com.callreminder

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

/** Maps the `ringtone` option to a sound URI. */
internal object SoundResolver {
  const val DEFAULT = "default"
  const val ALARM = "alarm"
  const val NOTIFICATION = "notification"
  const val SILENT = "silent"

  /**
   * Null means silent. Unknown raw names fall back to the device ringtone.
   *
   * Raw sounds are addressed by name (`android.resource://<pkg>/raw/<name>`),
   * never by numeric id: the URI is frozen into the notification channel, and
   * resource ids of a non-final R class are reassigned by later builds, which
   * would make an existing channel play a different (or missing) file.
   */
  fun resolve(context: Context, key: String): Uri? =
      when (key) {
        SILENT -> null
        DEFAULT -> systemDefault(RingtoneManager.TYPE_RINGTONE)
        ALARM -> systemDefault(RingtoneManager.TYPE_ALARM)
        NOTIFICATION -> systemDefault(RingtoneManager.TYPE_NOTIFICATION)
        else -> {
          val name = key.substringBeforeLast('.')
          if (CallResources.identifier(context, name, "raw") != null) {
            Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/raw/$name")
          } else {
            systemDefault(RingtoneManager.TYPE_RINGTONE)
          }
        }
      }

  /** Channel ids embed the sound key; keep them to safe characters. */
  fun channelSuffix(key: String): String = key.lowercase().replace(Regex("[^a-z0-9_]"), "_")

  private fun systemDefault(type: Int): Uri =
      RingtoneManager.getDefaultUri(type)
          ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
          ?: Settings.System.DEFAULT_NOTIFICATION_URI
}

internal object CallResources {
  private const val TAG = "CallReminder"
  private const val MAX_AVATAR_PX = 512

  /** Resolves a resource by name in the host app (drawable → mipmap for images). */
  @SuppressLint("DiscouragedApi") // Names come from JS config, so lookup by name is the point.
  fun identifier(context: Context, name: String, vararg types: String): Int? {
    if (name.isBlank()) return null
    for (type in types) {
      val id = context.resources.getIdentifier(name, type, context.packageName)
      if (id != 0) return id
    }
    return null
  }

  fun smallIcon(context: Context, config: CallReminderConfig): Int =
      config.smallIcon?.let { identifier(context, it, "drawable", "mipmap") }
          ?: R.drawable.callreminder_ic_notification

  /**
   * Caller avatar: the call's `avatarUri`, else the configured `largeIcon`,
   * else null (the call screen then shows the app icon). Only local sources are
   * supported — this runs on the main thread from receivers, so no network.
   */
  fun avatar(context: Context, config: CallReminderConfig, call: IncomingCall): Bitmap? =
      call.avatarUri?.let { loadUri(context, it) } ?: brandAvatar(context, config)

  /** The configured `largeIcon` only — never call-specific (used while details are hidden). */
  fun brandAvatar(context: Context, config: CallReminderConfig): Bitmap? =
      config.largeIcon?.let { loadNamed(context, it) }

  fun appIcon(context: Context): Bitmap? =
      try {
        context.packageManager.getApplicationIcon(context.packageName).toBitmap(MAX_AVATAR_PX / 2, MAX_AVATAR_PX / 2)
      } catch (error: Exception) {
        null
      }

  private fun loadUri(context: Context, value: String): Bitmap? {
    val uri = Uri.parse(value)
    return when (uri.scheme) {
      ContentResolver.SCHEME_FILE,
      ContentResolver.SCHEME_CONTENT,
      ContentResolver.SCHEME_ANDROID_RESOURCE -> decodeStream(context, uri)
      null -> loadNamed(context, value)
      else -> null
    }
  }

  private fun loadNamed(context: Context, name: String): Bitmap? {
    val id = identifier(context, name, "drawable", "mipmap") ?: return null
    return try {
      ContextCompat.getDrawable(context, id)?.let { drawable ->
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: MAX_AVATAR_PX / 2
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: MAX_AVATAR_PX / 2
        drawable.toBitmap(width.coerceAtMost(MAX_AVATAR_PX), height.coerceAtMost(MAX_AVATAR_PX))
      }
    } catch (error: Exception) {
      Log.w(TAG, "Unable to load avatar resource $name", error)
      null
    }
  }

  private fun decodeStream(context: Context, uri: Uri): Bitmap? =
      try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_AVATAR_PX && bounds.outHeight / (sample * 2) >= MAX_AVATAR_PX) {
          sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
      } catch (error: Exception) {
        Log.w(TAG, "Unable to load avatar $uri", error)
        null
      }
}
