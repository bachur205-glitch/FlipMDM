package com.moshe.flipmdm

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process

/**
 * Keeps the phone bootable:
 *  - core Android parts can never be hidden
 *  - system apps we hide are shown again right before shutdown and
 *    re-hidden after boot, so the phone always starts with them present
 */
object BootSafe {
    private const val PREFS = "bootsafe"
    private const val KEY = "system_hidden"

    private val alwaysProtected = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings",
        "com.android.shell",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    // Device-protected storage is readable even before the phone is unlocked.
    private fun prefs(c: Context) =
        c.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tracked(c: Context): Set<String> =
        prefs(c).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    private fun save(c: Context, s: Set<String>) {
        prefs(c).edit().putStringSet(KEY, s).commit()
    }

    private fun appInfo(c: Context, pkg: String): ApplicationInfo? = try {
        c.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** True for parts of Android the phone needs to start and run. */
    fun isCore(c: Context, pkg: String): Boolean {
        if (pkg in alwaysProtected || pkg.startsWith("com.android.providers.")) return true
        val ai = appInfo(c, pkg) ?: return false
        return ai.uid < Process.FIRST_APPLICATION_UID ||
            (ai.flags and ApplicationInfo.FLAG_PERSISTENT) != 0
    }

    fun isSystem(c: Context, pkg: String): Boolean =
        ((appInfo(c, pkg)?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0

    private fun dpm(c: Context) = c.getSystemService(DevicePolicyManager::class.java)

    /** Hide or show an app. Returns false if it wasn't allowed or didn't work. */
    fun setHidden(c: Context, pkg: String, hide: Boolean): Boolean {
        if (hide && isCore(c, pkg)) return false
        val admin = AdminReceiver.component(c)
        val dpm = dpm(c)
        dpm.setApplicationHidden(admin, pkg, hide)
        val ok = dpm.isApplicationHidden(admin, pkg) == hide
        if (ok && isSystem(c, pkg)) {
            val s = tracked(c).toMutableSet()
            if (hide) s.add(pkg) else s.remove(pkg)
            save(c, s)
        }
        return ok
    }

    /** Right before shutdown: show tracked system apps so the next boot can't get stuck. */
    fun beforeShutdown(c: Context) {
        val admin = AdminReceiver.component(c)
        val dpm = dpm(c)
        for (p in tracked(c)) dpm.setApplicationHidden(admin, p, false)
    }

    /** After boot finishes: hide them again. */
    fun afterBoot(c: Context) {
        val admin = AdminReceiver.component(c)
        val dpm = dpm(c)
        for (p in tracked(c)) if (!isCore(c, p)) dpm.setApplicationHidden(admin, p, true)
    }

    fun clearAll(c: Context) = save(c, emptySet())
}
