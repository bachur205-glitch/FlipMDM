package com.moshe.flipmdm

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After the phone finishes starting: re-hide system apps and start the guard. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        BootSafe.afterBoot(context)
        GuardService.start(context)
    }
}
