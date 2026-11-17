package com.moshe.flipmdm

import android.os.UserManager

/**
 * The user restrictions the app and the web dashboard both understand.
 * The keys are Android's own UserManager constants (no dots, so they're safe
 * as Firebase keys). If you add one here, add the same key to the dashboard.
 */
object Restrictions {
    val all: List<Pair<String, String>> = listOf(
        UserManager.DISALLOW_INSTALL_APPS to "Block installing apps",
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES to "Block APK sideloading",
        UserManager.DISALLOW_UNINSTALL_APPS to "Block uninstalling apps",
        UserManager.DISALLOW_FACTORY_RESET to "Block factory reset (Settings + reset codes)",
        UserManager.DISALLOW_SAFE_BOOT to "Block safe mode",
        UserManager.DISALLOW_ADD_USER to "Block adding users",
        UserManager.DISALLOW_MODIFY_ACCOUNTS to "Block adding accounts",
        UserManager.DISALLOW_CONFIG_DATE_TIME to "Block changing date/time",
        UserManager.DISALLOW_DEBUGGING_FEATURES to "Block USB debugging (also blocks adb reboot recovery)",
    )
}
