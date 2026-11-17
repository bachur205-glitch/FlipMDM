package com.moshe.flipmdm

import android.content.Context
import java.util.UUID

/** A stable id for this phone, used as its key in the dashboard database. */
object DeviceId {
    private const val PREFS = "mdm_remote"
    private const val KEY = "device_id"

    // Device-protected storage so the id is readable even before first unlock.
    private fun prefs(c: Context) =
        c.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(c: Context): String {
        prefs(c).getString(KEY, null)?.let { return it }
        val id = "flip-" + UUID.randomUUID().toString().take(8)
        prefs(c).edit().putString(KEY, id).commit()
        return id
    }
}
