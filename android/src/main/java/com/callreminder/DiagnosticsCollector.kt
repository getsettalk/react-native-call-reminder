package com.callreminder

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import java.lang.reflect.Method
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `getDiagnostics()`: a read-only snapshot of everything that can keep a
 * reminder call from reaching this device (`CallReminderDiagnostics` in JS).
 *
 * Every probe is independent and best-effort: one that throws (OEM quirks, a
 * missing hidden API, a dead system service) yields its documented fallback
 * instead of failing the snapshot. Needs no permission, never creates
 * notification channels and never starts the TTS engine. Runs on its own
 * thread because most probes are binder calls.
 */
internal object DiagnosticsCollector {
  private const val TAG = "CallReminder"
  private const val THREAD_NAME = "CallReminderDiagnostics"
  private const val MAX_EXIT_REASONS = 10
  /** Start records to scan for the current process (it is normally the newest). */
  private const val MAX_START_RECORDS = 5
  /** Upper bound for asking the main thread what the speech engine knows. */
  private const val MAIN_THREAD_WAIT_MS = 200L
  // Settings.Secure.TTS_DEFAULT_LOCALE is @hide; Android 12+ may refuse to read it.
  private const val SECURE_TTS_DEFAULT_LOCALE = "tts_default_locale"

  private const val UNKNOWN = "unknown"
  private const val NOT_APPLICABLE = "not_applicable"
  private const val FILTER_PRIORITY = "priority"
  private const val EXIT_USER_REQUESTED = "user_requested"
  private const val EXIT_USER_STOPPED = "user_stopped"

  // UsageStatsManager.STANDBY_BUCKET_*; EXEMPTED and NEVER are @hide, RESTRICTED is API 30.
  private const val BUCKET_EXEMPTED = 5
  private const val BUCKET_RESTRICTED = 45
  private const val BUCKET_NEVER = 50

  // NotificationManager.VISIBILITY_NO_OVERRIDE: the channel leaves visibility to each notification.
  private const val VISIBILITY_NO_OVERRIDE = -1000

  /**
   * ApplicationExitInfo.REASON_* by value, so it compiles against any
   * compileSdk and runs on every Android 11+ release (FREEZER is API 33,
   * PACKAGE_STATE_CHANGE / PACKAGE_UPDATED API 34).
   */
  private val EXIT_REASONS =
      mapOf(
          0 to UNKNOWN, // REASON_UNKNOWN
          1 to "exit_self", // REASON_EXIT_SELF
          2 to "signaled", // REASON_SIGNALED
          3 to "low_memory", // REASON_LOW_MEMORY
          4 to "crash", // REASON_CRASH
          5 to "crash_native", // REASON_CRASH_NATIVE
          6 to "anr", // REASON_ANR
          7 to "initialization_failure", // REASON_INITIALIZATION_FAILURE
          8 to "permission_change", // REASON_PERMISSION_CHANGE
          9 to "excessive_resource_usage", // REASON_EXCESSIVE_RESOURCE_USAGE
          10 to EXIT_USER_REQUESTED, // REASON_USER_REQUESTED
          11 to EXIT_USER_STOPPED, // REASON_USER_STOPPED
          12 to "dependency_died", // REASON_DEPENDENCY_DIED
          13 to "other", // REASON_OTHER
          14 to "freezer", // REASON_FREEZER
          15 to "package_state_change", // REASON_PACKAGE_STATE_CHANGE
          16 to "package_updated", // REASON_PACKAGE_UPDATED
      )

  /** An `ApplicationExitInfo` copied out, so API 30 types stay inside API-gated code. */
  private class Exit(
      val timestamp: Long,
      val reason: String,
      val status: Int,
      val importance: String,
      val description: String?,
      val processName: String?,
  )

  /** Collects on a background thread; [callback] runs on that thread. */
  fun collect(context: Context, activity: Activity?, callback: (Result<WritableMap>) -> Unit) {
    Thread({ callback(runCatching { snapshot(context, activity) }) }, THREAD_NAME)
        .apply { isDaemon = true }
        .start()
  }

  private fun snapshot(context: Context, activity: Activity?): WritableMap {
    val notifications = probe("notificationManager", null) { context.getSystemService(NotificationManager::class.java) }
    val power = probe("powerManager", null) { context.getSystemService(PowerManager::class.java) }
    val filter = probe("interruptionFilter", UNKNOWN) { interruptionFilter(notifications) }
    val exits = probe("exitReasons", emptyList()) { exitReasons(context) }
    val engine = probe("ttsEngine", null) { ttsEngine(context) }

    return Arguments.createMap().apply {
      putString("platform", "android")
      putString("osVersion", Build.VERSION.RELEASE ?: "")
      putInt("sdkInt", Build.VERSION.SDK_INT)
      putString("manufacturer", Build.MANUFACTURER.orEmpty().lowercase())
      putString("brand", Build.BRAND.orEmpty().lowercase())
      putString("model", Build.MODEL.orEmpty())
      putString("device", Build.DEVICE.orEmpty())
      putMap("rom", probe("rom", RomDetector.Rom(null, null, null)) { RomDetector.detect() }.toWritableMap())
      putMap(
          "permissions",
          probe("permissions", null) { PermissionsHelper.snapshot(context, activity) }
              ?: Arguments.createMap().apply {
                putString("platform", "android")
                putString("osVersion", Build.VERSION.RELEASE ?: "")
                putInt("sdkInt", Build.VERSION.SDK_INT)
              })
      putString("standbyBucket", probe("standbyBucket", UNKNOWN) { standbyBucket(context) })
      putBoolean("powerSaveMode", probe("powerSaveMode", false) { power?.isPowerSaveMode == true })
      putBoolean("deviceIdle", probe("deviceIdle", false) { power?.isDeviceIdleMode == true })
      putString("interruptionFilter", filter)
      putNullableBoolean("dndAllowsAlarms", probe("dndAllowsAlarms", null) { dndAllowsAlarms(notifications, filter) })
      putArray("notificationChannels", probe("notificationChannels", null) { channels(notifications) } ?: Arguments.createArray())
      putBoolean(
          "appNotificationsEnabled",
          probe("appNotificationsEnabled", false) { NotificationManagerCompat.from(context).areNotificationsEnabled() })
      putArray("exitReasons", Arguments.createArray().apply { exits.forEach { pushMap(it.toWritableMap()) } })
      putBoolean("forceStoppedRecently", probe("forceStoppedRecently", false) { forceStoppedRecently(context, exits) })
      putBoolean(
          "keyguardSecure",
          probe("keyguardSecure", false) { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true })
      putMap(
          "tts",
          Arguments.createMap().apply {
            putString("engine", engine)
            putString("defaultLanguage", ttsDefaultLanguage(context, engine))
          })
      putString("timezone", probe("timezone", "") { TimeZone.getDefault().id.orEmpty() })
      putString("locale", probe("locale", "") { Locale.getDefault().toLanguageTag() })
      putNull("ios")
      putDouble("collectedAt", System.currentTimeMillis().toDouble())
    }
  }

  // --- Probes -----------------------------------------------------------------

  /** Runs one probe; any failure is logged and replaced by [fallback]. */
  private inline fun <T> probe(name: String, fallback: T, block: () -> T): T =
      try {
        block()
      } catch (error: Exception) {
        Log.w(TAG, "Diagnostics: $name unavailable ($error)")
        fallback
      } catch (error: LinkageError) {
        // A ROM missing an API its SDK level promises.
        Log.w(TAG, "Diagnostics: $name unavailable ($error)")
        fallback
      }

  private fun standbyBucket(context: Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return NOT_APPLICABLE
    val usage = context.getSystemService(UsageStatsManager::class.java) ?: return UNKNOWN
    return when (usage.appStandbyBucket) {
      BUCKET_EXEMPTED -> "exempted"
      UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
      UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
      UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
      UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
      BUCKET_RESTRICTED -> "restricted"
      BUCKET_NEVER -> "never"
      else -> UNKNOWN
    }
  }

  private fun interruptionFilter(manager: NotificationManager?): String =
      when (manager?.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> FILTER_PRIORITY
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
        else -> UNKNOWN
      }

  /**
   * PRIORITY_CATEGORY_ALARMS of the DND policy: the consolidated policy of all
   * active modes while DND is on in priority mode (Android 11+), otherwise the
   * user's DND settings. Both are readable without notification-policy access.
   */
  private fun dndAllowsAlarms(manager: NotificationManager?, filter: String): Boolean? {
    // Android 7–8 had no alarms category: priority-only mode always let alarms through.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
    if (manager == null) return null
    val policy =
        if (filter == FILTER_PRIORITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          manager.consolidatedNotificationPolicy
        } else {
          manager.notificationPolicy
        } ?: return null
    return (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0
  }

  /** Every channel of the app (hosts use their own channels for ordinary reminders). */
  private fun channels(manager: NotificationManager?): WritableArray {
    val array = Arguments.createArray()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager == null) return array
    val blockedGroups =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          probe("channelGroups", emptySet()) {
            manager.notificationChannelGroups.filter { it.isBlocked }.map { it.id }.toSet()
          }
        } else {
          emptySet()
        }
    manager.notificationChannels.sortedBy { it.id }.forEach { channel ->
      array.pushMap(
          Arguments.createMap().apply {
            putString("id", channel.id)
            putString("name", channel.name?.toString().orEmpty())
            putString("importance", importance(channel.importance))
            putBoolean(
                "blocked",
                channel.importance == NotificationManager.IMPORTANCE_NONE ||
                    channel.group?.let { it in blockedGroups } == true)
            putBoolean("sound", channel.sound != null)
            putBoolean("vibration", channel.shouldVibrate())
            putBoolean("bypassDnd", channel.canBypassDnd())
            putString("lockscreenVisibility", lockscreenVisibility(channel.lockscreenVisibility))
          })
    }
    return array
  }

  private fun importance(value: Int): String =
      when (value) {
        NotificationManager.IMPORTANCE_NONE -> "none"
        NotificationManager.IMPORTANCE_MIN -> "min"
        NotificationManager.IMPORTANCE_LOW -> "low"
        NotificationManager.IMPORTANCE_DEFAULT -> "default"
        NotificationManager.IMPORTANCE_HIGH -> "high"
        NotificationManager.IMPORTANCE_MAX -> "max"
        else -> "unspecified"
      }

  private fun lockscreenVisibility(value: Int): String =
      when (value) {
        Notification.VISIBILITY_PUBLIC -> "public"
        Notification.VISIBILITY_PRIVATE -> "private"
        Notification.VISIBILITY_SECRET -> "secret"
        VISIBILITY_NO_OVERRIDE -> "no_override"
        else -> UNKNOWN
      }

  /** Android 11+: the app's latest process deaths (all processes), newest first. */
  private fun exitReasons(context: Context): List<Exit> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
    return activityManager
        .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_REASONS)
        .map { info ->
          Exit(
              timestamp = info.timestamp,
              reason = EXIT_REASONS[info.reason] ?: UNKNOWN,
              status = info.status,
              importance = processImportance(info.importance),
              description = info.description?.takeIf { it.isNotBlank() },
              processName = info.processName?.takeIf { it.isNotBlank() },
          )
        }
        .sortedByDescending { it.timestamp }
        .take(MAX_EXIT_REASONS)
  }

  @Suppress("DEPRECATION") // IMPORTANCE_EMPTY: still reported for empty processes.
  private fun processImportance(value: Int): String =
      when (value) {
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground"
        RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        RunningAppProcessInfo.IMPORTANCE_CACHED,
        RunningAppProcessInfo.IMPORTANCE_EMPTY -> "cached"
        RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
        else -> "other"
      }

  /**
   * Whether the app sat force-stopped (no pushes, no alarms) before this
   * process started. Android 15+: exact, from this process's start record —
   * except that a fresh install is "stopped" until its first launch too, so it
   * only counts when an earlier run left an exit record. Android 11–14: the
   * newest death of the main process was a user kill — Settings → Force stop
   * and OEM "swipe kills" record `user_requested`, but so does a stock-Android
   * swipe out of Recents, which does not stop the app.
   */
  private fun forceStoppedRecently(context: Context, exits: List<Exit>): Boolean {
    probe("startInfo", null) { wasForceStoppedBeforeStart(context) }?.let { stopped ->
      return stopped && exits.isNotEmpty()
    }
    val main = context.applicationInfo.processName ?: context.packageName
    val newest = exits.firstOrNull { it.processName == main } ?: exits.firstOrNull { it.processName == null }
    return newest?.reason == EXIT_USER_REQUESTED || newest?.reason == EXIT_USER_STOPPED
  }

  /**
   * Android 15+ only; null below it and when this process has no start record
   * (e.g. the feature is off on this build).
   */
  private fun wasForceStoppedBeforeStart(context: Context): Boolean? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
    val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
    val pid = Process.myPid()
    return activityManager
        .getHistoricalProcessStartReasons(MAX_START_RECORDS)
        .firstOrNull { it.pid == pid }
        ?.wasForceStopped()
  }

  /**
   * The engine `TextToSpeech` would use: the user's choice (Settings.Secure
   * TTS_DEFAULT_SYNTH) while it is installed, else the highest-ranked
   * installed engine (system engines first, like the framework). Visible on
   * Android 11+ thanks to the manifest's TTS_SERVICE `<queries>` entry.
   */
  private fun ttsEngine(context: Context): String? {
    val installed = probe("ttsEngines", emptyList()) { installedTtsEngines(context) }
    val configured =
        probe("ttsDefaultSynth", null) {
          Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        }?.takeIf { it.isNotBlank() }
    return configured?.takeIf { installed.isEmpty() || it in installed } ?: installed.firstOrNull()
  }

  private fun installedTtsEngines(context: Context): List<String> {
    val pm = context.packageManager
    val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
    val services =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
          @Suppress("DEPRECATION") pm.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    return services
        .filter { it.serviceInfo != null }
        .sortedWith(compareByDescending<ResolveInfo> { it.isSystemService() }.thenByDescending { it.priority })
        .map { it.serviceInfo.packageName }
        .distinct()
  }

  private fun ResolveInfo.isSystemService(): Boolean =
      (serviceInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

  /**
   * What the library's speech engine reports when it is already running in
   * this process (it is never started for this), else the system TTS locale
   * setting where it can be read; null otherwise.
   */
  private fun ttsDefaultLanguage(context: Context, engine: String?): String? =
      probe("ttsEngineLanguage", null) { onMainThread { SpeechEngine.peek()?.defaultLanguageIfReady() } }
          ?: probe("ttsLocaleSetting", null) { ttsLocaleSetting(context, engine) }

  /** "engine:tag" pairs separated by commas (old releases: a bare tag). */
  private fun ttsLocaleSetting(context: Context, engine: String?): String? {
    val raw = Settings.Secure.getString(context.contentResolver, SECURE_TTS_DEFAULT_LOCALE)?.trim()
    if (raw.isNullOrEmpty()) return null
    val entries = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val tag =
        if (entries.any { ':' in it }) {
          engine?.let { name -> entries.firstOrNull { it.startsWith("$name:") }?.substringAfter(':') }
        } else {
          entries.firstOrNull()
        }
    return tag?.replace('_', '-')?.takeIf { it.isNotBlank() }
  }

  /**
   * Runs [block] on the main thread (where [SpeechEngine] lives) and waits at
   * most [MAIN_THREAD_WAIT_MS]: null on timeout, never an exception on the main thread.
   */
  private fun <T> onMainThread(block: () -> T?): T? {
    if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
    val result = AtomicReference<T?>()
    val done = CountDownLatch(1)
    Handler(Looper.getMainLooper()).post {
      try {
        result.set(runCatching(block).getOrNull())
      } finally {
        done.countDown()
      }
    }
    return if (done.await(MAIN_THREAD_WAIT_MS, TimeUnit.MILLISECONDS)) result.get() else null
  }

  private fun WritableMap.putNullableBoolean(key: String, value: Boolean?) {
    if (value == null) putNull(key) else putBoolean(key, value)
  }

  private fun Exit.toWritableMap(): WritableMap =
      Arguments.createMap().apply {
        putDouble("timestamp", timestamp.toDouble())
        putString("reason", reason)
        putInt("status", status)
        putString("importance", importance)
        putString("description", description)
        putString("processName", processName)
      }

  private fun RomDetector.Rom.toWritableMap(): WritableMap =
      Arguments.createMap().apply {
        putString("name", name)
        putString("version", version)
        putString("display", display)
      }
}

/**
 * Best-effort detection of the OEM's Android skin from system properties,
 * read through `android.os.SystemProperties` by reflection (no permission;
 * every failure means "not this skin"). Order matters: OnePlus and realme
 * builds also carry the ColorOS properties, HyperOS keeps the MIUI ones and
 * HarmonyOS keeps the EMUI ones.
 */
@SuppressLint("PrivateApi") // android.os.SystemProperties: read-only, on the SDK's unsupported (not blocked) list.
internal object RomDetector {
  class Rom(val name: String?, val version: String?, val display: String?)

  private val getter: Method? by lazy {
    try {
      Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
    } catch (error: Exception) {
      null
    } catch (error: LinkageError) {
      null
    }
  }

  /** A system property, or null when it is empty or cannot be read. */
  fun prop(key: String): String? =
      try {
        (getter?.invoke(null, key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
      } catch (error: Exception) {
        null
      } catch (error: LinkageError) {
        null
      }

  fun detect(): Rom {
    val display = Build.DISPLAY?.trim()?.takeIf { it.isNotEmpty() }
    val names = "${Build.BRAND} ${Build.MANUFACTURER}".lowercase()
    fun rom(name: String, version: String?) = Rom(name, version, display)

    // OPPO / realme / OnePlus (ColorOS family).
    prop("ro.build.version.realmeui")?.let { return rom("realme UI", it) }
    val colorOs = prop("ro.build.version.oplusrom") ?: prop("ro.build.version.opporom")
    if ("oneplus" in names) {
      val oxygen = prop("ro.oxygen.version")
      if (oxygen != null || colorOs != null) return rom("OxygenOS", oxygen ?: colorOs)
    }
    colorOs?.let { return rom("ColorOS", it) }
    // Xiaomi / Redmi / POCO.
    prop("ro.mi.os.version.name")?.let { return rom("HyperOS", it) }
    prop("ro.miui.ui.version.name")?.let { return rom("MIUI", it) }
    // vivo / iQOO.
    val vivoName = prop("ro.vivo.os.build.display.id") ?: prop("ro.vivo.os.name")
    val vivoVersion = prop("ro.vivo.os.version")
    if (vivoName != null || vivoVersion != null) {
      val name = if (vivoName?.contains("origin", ignoreCase = true) == true) "OriginOS" else "Funtouch OS"
      return rom(name, vivoVersion)
    }
    // Honor / Huawei.
    prop("ro.build.version.magic")?.let {
      return rom(if (it.startsWith("MagicUI", ignoreCase = true)) "Magic UI" else "MagicOS", afterPrefix(it))
    }
    prop("hw_sc.build.platform.version")?.let { return rom("HarmonyOS", it) }
    prop("ro.build.version.emui")?.let { return rom("EMUI", afterPrefix(it)) }
    // Samsung.
    prop("ro.build.version.oneui")?.let { return rom("One UI", oneUiVersion(it)) }
    // Meizu.
    if (display?.contains("flyme", ignoreCase = true) == true) return rom("Flyme", null)
    return Rom(null, null, display)
  }

  /** `EmotionUI_12.0.0` → `12.0.0`, `MagicOS_7.1` → `7.1`. */
  private fun afterPrefix(value: String): String = value.substringAfter('_').ifEmpty { value }

  /** `60100` → `6.1`, `50101` → `5.1.1`. */
  private fun oneUiVersion(value: String): String {
    val code = value.toIntOrNull() ?: return value
    val major = code / 10000
    val minor = code / 100 % 100
    val patch = code % 100
    return if (patch == 0) "$major.$minor" else "$major.$minor.$patch"
  }
}
