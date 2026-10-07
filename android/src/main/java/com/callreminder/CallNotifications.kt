package com.callreminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.ContextCompat

/** Intents shared by notifications, the call screen and the timeout alarm. */
internal object CallIntents {
  const val EXTRA_CALL_ID = "com.callreminder.extra.CALL_ID"
  const val ACTION_SHOW = "com.callreminder.action.SHOW"
  const val ACTION_ANSWER = "com.callreminder.action.ANSWER"
  const val ACTION_DECLINE = "com.callreminder.action.DECLINE"
  const val ACTION_DISMISSED = "com.callreminder.action.DISMISSED"
  const val ACTION_TIMEOUT = "com.callreminder.action.TIMEOUT"

  private const val SLOT_SHOW = 0
  private const val SLOT_ANSWER = 1
  private const val SLOT_DECLINE = 2
  private const val SLOT_DISMISSED = 3
  private const val SLOT_TIMEOUT = 4

  private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

  fun screenIntent(context: Context, callId: String, action: String): Intent =
      Intent(context, IncomingCallActivity::class.java)
          .setAction(action)
          .setData(callUri(callId, action))
          .putExtra(EXTRA_CALL_ID, callId)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

  fun show(context: Context, callId: String): PendingIntent =
      PendingIntent.getActivity(
          context, requestCode(callId, SLOT_SHOW), screenIntent(context, callId, ACTION_SHOW), FLAGS)

  /** Answer opens the call screen directly: notification trampolines are blocked on Android 12+. */
  fun answer(context: Context, callId: String): PendingIntent =
      PendingIntent.getActivity(
          context,
          requestCode(callId, SLOT_ANSWER),
          screenIntent(context, callId, ACTION_ANSWER),
          FLAGS)

  fun decline(context: Context, callId: String): PendingIntent =
      broadcast(context, callId, ACTION_DECLINE, SLOT_DECLINE)

  fun dismissed(context: Context, callId: String): PendingIntent =
      broadcast(context, callId, ACTION_DISMISSED, SLOT_DISMISSED)

  fun timeout(context: Context, callId: String): PendingIntent =
      broadcast(context, callId, ACTION_TIMEOUT, SLOT_TIMEOUT)

  private fun broadcast(context: Context, callId: String, action: String, slot: Int): PendingIntent =
      PendingIntent.getBroadcast(
          context,
          requestCode(callId, slot),
          Intent(context, CallActionReceiver::class.java)
              .setAction(action)
              .setData(callUri(callId, action))
              .putExtra(EXTRA_CALL_ID, callId),
          FLAGS)

  /**
   * Distinct per call and per action. The data URI already makes the intents
   * unequal (so PendingIntents never overwrite each other); the request code
   * keeps them apart on launchers/OEMs that compare request codes only.
   */
  private fun requestCode(callId: String, slot: Int): Int = (callId.hashCode() and 0x0FFFFFFF) * 8 + slot

  private fun callUri(callId: String, action: String): Uri =
      Uri.Builder()
          .scheme("callreminder")
          .authority("call")
          .appendPath(callId)
          .appendPath(action.substringAfterLast('.').lowercase())
          .build()
}

/** Builds, posts and cancels the call notifications and owns the call channel(s). */
internal object CallNotifications {
  private const val TAG = "CallReminder"
  /** Tag namespace so our ids can never collide with the host app's notifications. */
  private const val NOTIFICATION_TAG = "com.callreminder.call"

  fun notificationId(callId: String): Int = callId.hashCode()

  /** Channel for a call's ring sound (a channel's sound is fixed once created). */
  fun channelIdFor(config: CallReminderConfig, ringtone: String?): String {
    val key = ringtone ?: config.ringtone
    return if (key == config.ringtone) config.channelId
    else "${config.channelId}_${SoundResolver.channelSuffix(key)}"
  }

  fun ensureChannel(context: Context, config: CallReminderConfig, ringtone: String? = null): String {
    val channelId = channelIdFor(config, ringtone)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return channelId
    val manager = context.getSystemService(NotificationManager::class.java) ?: return channelId
    val existing = manager.getNotificationChannel(channelId)
    if (existing != null) {
      // Only name/description may change after creation; the user owns the rest.
      existing.name = config.channelName(context)
      existing.description = config.channelDescription(context)
      manager.createNotificationChannel(existing)
      return channelId
    }
    val soundKey = ringtone ?: config.ringtone
    val channel =
        NotificationChannel(channelId, config.channelName(context), NotificationManager.IMPORTANCE_HIGH).apply {
          description = config.channelDescription(context)
          lockscreenVisibility = Notification.VISIBILITY_PUBLIC
          setShowBadge(false)
          enableLights(true)
          lightColor = config.accentColor(context)
          val pattern = config.vibrationPattern
          enableVibration(pattern.any { it > 0 })
          if (pattern.any { it > 0 }) {
            vibrationPattern = pattern
          }
          val sound = SoundResolver.resolve(context, soundKey)
          if (sound == null) {
            setSound(null, null)
          } else {
            setSound(
                sound,
                AudioAttributes.Builder()
                    .setUsage(config.ringUsage.usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
          }
        }
    manager.createNotificationChannel(channel)
    return channelId
  }

  /** Channel importance, or null below Android O / when the channel is missing. */
  fun channelImportance(context: Context, channelId: String): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    return context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId)?.importance
  }

  fun canPost(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
  }

  /**
   * The ringing notification: insistent sound, Answer/Decline and a full-screen
   * intent. The full-screen intent is always set: when the app may not use it
   * (Android 14+ without USE_FULL_SCREEN_INTENT) the system keeps the call as a
   * sticky, expanded heads-up for about a minute instead, which it only does
   * for notifications that asked for full screen.
   */
  fun postRinging(context: Context, config: CallReminderConfig, record: CallRecord): Boolean {
    if (config.ringingStyle == RingingStyle.CALL) {
      // Some OEM builds reject a call-style notification they don't like; a
      // reminder must still ring, so fall back to the standard template.
      try {
        return post(context, record.call.callId, buildRinging(context, config, record, callStyle = true))
      } catch (e: RuntimeException) {
        Log.w(TAG, "Call-style notification rejected, falling back to standard", e)
      }
    }
    return post(context, record.call.callId, buildRinging(context, config, record, callStyle = false))
  }

  private fun buildRinging(
      context: Context,
      config: CallReminderConfig,
      record: CallRecord,
      callStyle: Boolean,
  ): Notification {
    val call = record.call
    val channelId = ensureChannel(context, config, call.ringtone)
    val builder =
        baseBuilder(context, config, record, channelId)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(CallIntents.dismissed(context, call.callId))
    if (callStyle) {
      // Answer/Decline come from the template (green/red pills); the caller is the app.
      val caller =
          Person.Builder()
              .setName(call.callerName)
              .setImportant(true)
              .apply { CallResources.avatar(context, config, call)?.let { setIcon(IconCompat.createWithBitmap(it)) } }
              .build()
      builder.setStyle(
          NotificationCompat.CallStyle.forIncomingCall(
                  caller, CallIntents.decline(context, call.callId), CallIntents.answer(context, call.callId))
              .setVerificationText(call.title))
    } else {
      builder
          .addAction(
              R.drawable.callreminder_ic_call_end,
              config.label(context, Label.DECLINE),
              CallIntents.decline(context, call.callId))
          .addAction(
              R.drawable.callreminder_ic_call,
              config.label(context, Label.ANSWER),
              CallIntents.answer(context, call.callId))
    }

    val remaining = record.deadlineAt - System.currentTimeMillis()
    if (remaining > 0) {
      // System-enforced removal, even if our process is gone when it is due.
      builder.setTimeoutAfter(remaining)
    }
    builder.setFullScreenIntent(CallIntents.show(context, call.callId), true)
    if (record.silenced) {
      builder.setSilent(true)
    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      applyLegacyAlerting(context, config, builder, call.ringtone ?: config.ringtone)
    }
    val notification = builder.build()
    if (!record.silenced) {
      // Repeat sound + vibration until answered, declined or timed out.
      notification.flags = notification.flags or Notification.FLAG_INSISTENT
    }
    // Not removable with "Clear all" on any Android version.
    notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
    return notification
  }

  /**
   * Shown instead of ringing while the user is on another call: no sound, no
   * heads-up, but it can still be answered from the shade until the call's
   * timeout, when it is removed and reported as `timeout`.
   */
  fun postQuiet(context: Context, config: CallReminderConfig, record: CallRecord): Boolean {
    val call = record.call
    val channelId = ensureChannel(context, config, call.ringtone)
    val builder =
        baseBuilder(context, config, record, channelId)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setAutoCancel(true)
            .setSubText(config.label(context, Label.TAP_TO_ANSWER))
            .setDeleteIntent(CallIntents.dismissed(context, call.callId))
            .addAction(
                R.drawable.callreminder_ic_call,
                config.label(context, Label.ANSWER),
                CallIntents.answer(context, call.callId))
    val remaining = record.deadlineAt - System.currentTimeMillis()
    if (remaining > 0) {
      // Same expiry as a ringing call, enforced even if our process is gone.
      builder.setTimeoutAfter(remaining)
    }
    return post(context, call.callId, builder.build())
  }

  fun cancel(context: Context, callId: String) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, notificationId(callId))
  }

  private fun baseBuilder(
      context: Context,
      config: CallReminderConfig,
      record: CallRecord,
      channelId: String,
  ): NotificationCompat.Builder {
    val call = record.call
    val builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(CallResources.smallIcon(context, config))
            .setColor(config.accentColor(context))
            .setContentTitle(call.title)
            .setContentText(call.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(call.body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setShowWhen(true)
            .setWhen(record.createdAt)
            .setContentIntent(CallIntents.show(context, call.callId))
            .setVisibility(
                if (call.privateOnLockScreen) NotificationCompat.VISIBILITY_PRIVATE
                else NotificationCompat.VISIBILITY_PUBLIC)
    // The header already names the app; show the caller only when it is someone else.
    val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
    if (!call.callerName.equals(appLabel, ignoreCase = true)) {
      builder.setSubText(call.callerName)
    }
    CallResources.avatar(context, config, call)?.let { builder.setLargeIcon(it) }
    if (call.privateOnLockScreen) {
      builder.setPublicVersion(
          NotificationCompat.Builder(context, channelId)
              .setSmallIcon(CallResources.smallIcon(context, config))
              .setColor(config.accentColor(context))
              .setContentTitle(config.label(context, Label.INCOMING_TITLE))
              .setContentText(config.brandName(context))
              .setCategory(NotificationCompat.CATEGORY_ALARM)
              .build())
    }
    return builder
  }

  /** Pre-O devices take sound/vibration from the notification, not a channel. */
  @Suppress("DEPRECATION")
  private fun applyLegacyAlerting(
      context: Context,
      config: CallReminderConfig,
      builder: NotificationCompat.Builder,
      soundKey: String,
  ) {
    SoundResolver.resolve(context, soundKey)?.let {
      builder.setSound(it, CallAudio.legacyStreamFor(config.ringUsage.usage))
    }
    if (config.vibrationPattern.any { it > 0 }) {
      builder.setVibrate(config.vibrationPattern)
    }
    builder.setLights(config.accentColor(context), 1000, 1000)
  }

  private fun post(context: Context, callId: String, notification: Notification): Boolean {
    if (!canPost(context)) return false
    return try {
      NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, notificationId(callId), notification)
      true
    } catch (error: SecurityException) {
      Log.w(TAG, "Notification permission revoked while posting", error)
      false
    }
  }
}
