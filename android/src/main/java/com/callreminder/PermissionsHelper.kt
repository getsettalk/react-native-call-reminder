package com.callreminder

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Permission/setting states as reported to JS (`PermissionState`). */
internal object PermissionState {
  const val GRANTED = "granted"
  const val DENIED = "denied"
  const val NOT_DETERMINED = "not_determined"
  const val NOT_APPLICABLE = "not_applicable"
  const val UNKNOWN = "unknown"

  fun of(granted: Boolean) = if (granted) GRANTED else DENIED
}

/** The app's battery usage setting as reported to JS (`BatteryUsage`). */
internal object BatteryUsage {
  const val UNRESTRICTED = "unrestricted"
  const val OPTIMIZED = "optimized"
  const val RESTRICTED = "restricted"
  const val UNKNOWN = "unknown"
}

internal object PermissionsHelper {
  private const val PREFS = "com.callreminder.permissions"
  private const val KEY_NOTIFICATIONS_ASKED = "notificationsAsked"
  // Not in the public SDK; readable on AOSP and most OEM builds.
  private const val SECURE_LOCK_SCREEN_SHOW_NOTIFICATIONS = "lock_screen_show_notifications"
  // Settings.ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL (@hide): the app's battery usage page.
  private const val ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL = "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL"

  fun snapshot(context: Context, activity: Activity?): WritableMap {
    val config = ConfigStore.load(context)
    val notifications = notifications(context, activity)
    val channel = channel(context, config)
    val fullScreen = fullScreenIntent(context)
    val oemManager = OemAutoStart.hasManager(context)
    return Arguments.createMap().apply {
      putString("platform", "android")
      putString("osVersion", Build.VERSION.RELEASE ?: "")
      putInt("sdkInt", Build.VERSION.SDK_INT)
      putString("manufacturer", Build.MANUFACTURER.orEmpty().lowercase())
      putString("notifications", notifications)
      putString("channel", channel)
      putString("fullScreenIntent", fullScreen)
      putString("exactAlarm", exactAlarm(context))
      val battery = batteryUsage(context)
      putString("batteryOptimization", batteryOptimization(battery))
      putString("batteryUsage", battery)
      putBoolean("backgroundRestricted", battery == BatteryUsage.RESTRICTED)
      putString("autoStart", if (oemManager) PermissionState.UNKNOWN else PermissionState.NOT_APPLICABLE)
      putBoolean("oemHasAutoStartManager", oemManager)
      putString("timeSensitive", PermissionState.NOT_APPLICABLE)
      putString("criticalAlerts", PermissionState.NOT_APPLICABLE)
      putString("lockScreen", lockScreen(context, config))
      putBoolean(
          "allRequiredGranted",
          notifications == PermissionState.GRANTED &&
              (channel == PermissionState.GRANTED || channel == PermissionState.NOT_APPLICABLE) &&
              fullScreen == PermissionState.GRANTED)
    }
  }

  fun notifications(context: Context, activity: Activity?): String {
    val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return PermissionState.of(enabled)
    }
    val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    if (granted) return PermissionState.of(enabled)
    val askedBefore =
        prefs(context).getBoolean(KEY_NOTIFICATIONS_ASKED, false) ||
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
    return if (askedBefore) PermissionState.DENIED else PermissionState.NOT_DETERMINED
  }

  fun markNotificationsAsked(context: Context) {
    prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
  }

  /**
   * The call channel must be enabled at HIGH importance to ring and pop up.
   * Read-only: a channel that does not exist yet (configure() not called) is
   * `not_determined` — creating it here would freeze default sound/vibration
   * into a channel the user could see but the app could never change.
   */
  fun channel(context: Context, config: CallReminderConfig): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return PermissionState.NOT_APPLICABLE
    val importance =
        CallNotifications.channelImportance(context, config.channelId) ?: return PermissionState.NOT_DETERMINED
    return PermissionState.of(importance >= NotificationManager.IMPORTANCE_HIGH)
  }

  fun fullScreenIntent(context: Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return PermissionState.GRANTED
    val manager = context.getSystemService(NotificationManager::class.java) ?: return PermissionState.UNKNOWN
    return PermissionState.of(manager.canUseFullScreenIntent())
  }

  fun canUseFullScreenIntent(context: Context): Boolean = fullScreenIntent(context) == PermissionState.GRANTED

  /**
   * The library does not declare SCHEDULE_EXACT_ALARM itself; for a host app
   * that does not either, there is nothing the user could grant, so the state
   * is `not_applicable` rather than a `denied` that can never be fixed.
   */
  fun exactAlarm(context: Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return PermissionState.GRANTED
    if (!declaresExactAlarm(context)) return PermissionState.NOT_APPLICABLE
    val manager = context.getSystemService(AlarmManager::class.java) ?: return PermissionState.UNKNOWN
    return PermissionState.of(manager.canScheduleExactAlarms())
  }

  private fun declaresExactAlarm(context: Context): Boolean {
    val requested =
        try {
          val pm = context.packageManager
          val info =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
              } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
              }
          info.requestedPermissions.orEmpty()
        } catch (error: PackageManager.NameNotFoundException) {
          emptyArray<String>()
        }
    return Manifest.permission.SCHEDULE_EXACT_ALARM in requested || Manifest.permission.USE_EXACT_ALARM in requested
  }

  /**
   * - `restricted`: background-restricted by the user (Android 9+; on Android
   *   14/15 "Allow background usage" off / "Restricted"). High-priority FCM
   *   messages are then held back and the app cannot start in the background.
   * - `unrestricted`: exempt from battery optimisation.
   * - `optimized`: the Android default ("Allow background usage" on). Doze
   *   still delivers high-priority FCM messages at once, so calls ring.
   * (minSdk is 24, so the pre-Marshmallow "no optimisation at all" case never applies.)
   */
  fun batteryUsage(context: Context): String {
    if (isBackgroundRestricted(context)) return BatteryUsage.RESTRICTED
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return BatteryUsage.UNKNOWN
    return if (power.isIgnoringBatteryOptimizations(context.packageName)) BatteryUsage.UNRESTRICTED
    else BatteryUsage.OPTIMIZED
  }

  fun isBackgroundRestricted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
    return activityManager.isBackgroundRestricted
  }

  /**
   * Whether battery management stands in the way of reminder calls: `denied`
   * only while background-restricted. Being merely battery-*optimized* (the
   * default) is `granted` — reporting it as a problem would be a false alarm.
   */
  fun batteryOptimization(context: Context): String = batteryOptimization(batteryUsage(context))

  private fun batteryOptimization(batteryUsage: String): String =
      when (batteryUsage) {
        BatteryUsage.RESTRICTED -> PermissionState.DENIED
        BatteryUsage.UNKNOWN -> PermissionState.UNKNOWN
        else -> PermissionState.GRANTED
      }

  fun lockScreen(context: Context, config: CallReminderConfig): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
          context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(config.channelId)
      if (channel?.lockscreenVisibility == Notification.VISIBILITY_SECRET) {
        return PermissionState.DENIED
      }
    }
    return try {
      PermissionState.of(
          Settings.Secure.getInt(context.contentResolver, SECURE_LOCK_SCREEN_SHOW_NOTIFICATIONS, 1) != 0)
    } catch (error: Exception) {
      PermissionState.UNKNOWN
    }
  }

  // --- Settings intents ------------------------------------------------------

  fun fullScreenIntentSettings(context: Context): List<Intent> =
      buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          add(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context)))
        }
        addAll(notificationSettings(context, null))
      }

  fun exactAlarmSettings(context: Context): List<Intent> =
      buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          add(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context)))
        }
        add(appDetails(context))
      }

  /**
   * The most specific page that lets the user change this app's battery usage,
   * keeping only screens that resolve on this device:
   * 1. The app's own battery page (AOSP Settings' `AdvancedPowerUsageDetailActivity`,
   *    reached with `android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL` + `package:`
   *    data): "Background restriction" on Android 9–11, Unrestricted / Optimized /
   *    Restricted on 12–13, "Allow background usage" on 14+. Hidden action, so
   *    OEM builds may lack it — hence the resolve check.
   * 2. The app's details page (always present; its Battery entry leads to the same).
   * 3. The battery-optimisation list.
   * We deliberately do not use ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (needs a
   * Play-restricted permission the library never declares).
   */
  fun batteryOptimizationSettings(context: Context): List<Intent> =
      listOf(
              Intent(ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL, packageUri(context)),
              appDetails(context),
              Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
          .filter { resolves(context, it) }
          .ifEmpty { listOf(appDetails(context)) }

  /** Visible thanks to the `<queries>` entries in the library manifest. */
  private fun resolves(context: Context, intent: Intent): Boolean =
      try {
        intent.resolveActivity(context.packageManager) != null
      } catch (error: RuntimeException) {
        false
      }

  fun notificationSettings(context: Context, channelId: String?): List<Intent> =
      buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          val channelExists =
              channelId != null &&
                  context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId) != null
          if (channelExists) {
            add(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId))
          }
          add(
              Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                  .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        } else {
          add(
              Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                  .putExtra("app_package", context.packageName)
                  .putExtra("app_uid", context.applicationInfo.uid))
        }
        add(appDetails(context))
      }

  fun appDetails(context: Context): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

  private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

  private fun prefs(context: Context) =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
