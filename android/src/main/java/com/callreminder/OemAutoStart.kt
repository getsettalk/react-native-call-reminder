package com.callreminder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Deep links into OEM "auto-start" / background-launch managers. These screens
 * are undocumented and move between OS versions, so each OEM has a list of
 * known components tried in order. Whether the user enabled auto-start cannot
 * be read back on any OEM — only whether such a manager exists.
 *
 * The packages below are declared in the library manifest's `<queries>` so they
 * are visible on Android 11+.
 */
internal object OemAutoStart {
  private class Target(val pkg: String, val cls: String? = null, val action: String? = null) {
    fun intent(context: Context): Intent {
      val intent = if (action != null) Intent(action) else Intent()
      if (cls != null) intent.component = ComponentName(pkg, cls) else intent.setPackage(pkg)
      // Meizu and some MIUI builds read the target package from extras.
      intent.putExtra("packageName", context.packageName)
      intent.putExtra("package_name", context.packageName)
      intent.putExtra("pkg_name", context.packageName)
      return intent
    }
  }

  private val XIAOMI =
      listOf(
          Target("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
          Target("com.miui.securitycenter", action = "miui.intent.action.OP_AUTO_START"),
          Target("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
      )
  private val OPPO =
      listOf(
          Target("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
          Target("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
          Target("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
          Target("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
          Target("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
          Target("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerControlActivity"),
      )
  private val ONEPLUS =
      listOf(Target("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")) + OPPO
  private val VIVO =
      listOf(
          Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
          Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
          Target("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
      )
  private val HUAWEI =
      listOf(
          Target("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
          Target("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
          Target("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
      )
  private val HONOR =
      listOf(
          Target("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
      ) + HUAWEI
  private val SAMSUNG =
      listOf(
          Target("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
          Target("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
          Target("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
          Target("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
      )
  private val ASUS =
      listOf(
          Target("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
          Target("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
      )
  private val LETV = listOf(Target("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"))
  private val MEIZU = listOf(Target("com.meizu.safe", action = "com.meizu.safe.security.SHOW_APPSEC"))
  private val NOKIA =
      listOf(Target("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"))

  private fun targetsFor(manufacturer: String, brand: String): List<Target> {
    val names = setOf(manufacturer.lowercase(), brand.lowercase())
    fun any(vararg candidates: String) = candidates.any { it in names }
    return when {
      any("xiaomi", "redmi", "poco") -> XIAOMI
      any("oneplus") -> ONEPLUS
      any("oppo", "realme") -> OPPO
      any("vivo", "iqoo") -> VIVO
      any("honor") -> HONOR
      any("huawei") -> HUAWEI
      any("samsung") -> SAMSUNG
      any("asus") -> ASUS
      any("letv", "leeco") -> LETV
      any("meizu") -> MEIZU
      any("nokia", "hmd global", "hmd") -> NOKIA
      else -> emptyList()
    }
  }

  private fun targets(): List<Target> = targetsFor(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())

  fun hasManager(context: Context): Boolean =
      targets().any { target -> resolves(context, target.intent(context)) }

  /** Opens the first resolvable manager. Must be called on the main thread. */
  fun open(context: Context, launch: (Intent) -> Boolean): Boolean =
      targets().any { target ->
        val intent = target.intent(context)
        resolves(context, intent) && launch(intent)
      }

  private fun resolves(context: Context, intent: Intent): Boolean {
    val pm = context.packageManager
    val info =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
          @Suppress("DEPRECATION") pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    return info?.activityInfo?.exported == true
  }
}
