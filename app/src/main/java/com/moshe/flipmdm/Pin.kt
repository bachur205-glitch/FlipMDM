package com.moshe.flipmdm

import android.content.Context
import java.security.MessageDigest

/** Stores only a SHA-256 hash of the PIN, never the PIN itself. */
object Pin {
    private const val PREFS = "mdm"
    private const val KEY = "pin_hash"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("flipmdm:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun isSet(c: Context) = prefs(c).contains(KEY)
    fun set(c: Context, pin: String) = prefs(c).edit().putString(KEY, hash(pin)).apply()
    fun check(c: Context, pin: String) = prefs(c).getString(KEY, null) == hash(pin)
    fun clear(c: Context) = prefs(c).edit().remove(KEY).apply()
}
