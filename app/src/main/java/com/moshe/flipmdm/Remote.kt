package com.moshe.flipmdm

import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

/**
 * Remote control over Firebase Realtime Database.
 *
 *  - The phone signs in anonymously and owns the node /devices/<deviceId>.
 *  - The web dashboard (signed in with Google) writes:
 *       desiredState  -> restrictions + factory-reset protection to enforce
 *       commands/<id> -> one-shot actions (lock, reboot, hide/show app, wipe)
 *  - The phone writes status back (online, battery, what's applied, FRP state).
 *
 * Everything here is best-effort: if Firebase isn't configured, or the network
 * is down, the phone keeps working locally and this just stays quiet.
 */
object Remote {
    private const val TAG = "FlipMDMRemote"

    @Volatile private var started = false
    private var ref: DatabaseReference? = null
    private var stateListener: ValueEventListener? = null
    private var cmdListener: ChildEventListener? = null

    fun isConfigured(c: Context): Boolean =
        c.getString(R.string.mdm_database_url).trim().isNotEmpty()

    fun deviceId(c: Context): String = DeviceId.get(c)

    /** Idempotent: safe to call from the service, on boot, and from the UI. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        val c = context.applicationContext
        val url = c.getString(R.string.mdm_database_url).trim()
        if (url.isEmpty()) return
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(c.packageName)) return
        try {
            val db = FirebaseDatabase.getInstance(url)
            try { db.setPersistenceEnabled(true) } catch (_: Exception) {}
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously().addOnCompleteListener { t ->
                    if (t.isSuccessful) attach(c, db)
                    else Log.w(TAG, "anonymous sign-in failed", t.exception)
                }
            } else {
                attach(c, db)
            }
            started = true
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
        }
    }

    @Synchronized
    fun stop() {
        try {
            val r = ref
            stateListener?.let { r?.child("desiredState")?.removeEventListener(it) }
            cmdListener?.let { r?.child("commands")?.removeEventListener(it) }
        } catch (_: Exception) {}
        stateListener = null
        cmdListener = null
        ref = null
        started = false
    }

    private fun attach(c: Context, db: FirebaseDatabase) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val r = db.getReference("devices/${DeviceId.get(c)}")
        ref = r

        // Claim this node and set up presence.
        r.child("deviceAuthUid").setValue(uid)
        r.child("status/online").onDisconnect().setValue(false)
        r.child("status/lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)

        stateListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                try { applyDesiredState(c, s) } catch (e: Exception) { Log.w(TAG, "apply", e) }
            }
            override fun onCancelled(e: DatabaseError) { Log.w(TAG, "state cancelled: ${e.message}") }
        }
        r.child("desiredState").addValueEventListener(stateListener!!)

        cmdListener = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) = handleCommand(c, s)
            override fun onChildChanged(s: DataSnapshot, prev: String?) = handleCommand(c, s)
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        r.child("commands").addChildEventListener(cmdListener!!)

        // Heartbeat whenever the realtime connection comes up.
        db.getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.getValue(Boolean::class.java) == true) heartbeat(c)
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        publishApps(c, r)
        heartbeat(c)
    }

    // ---------- enforce desired state ----------

    private fun applyDesiredState(c: Context, snap: DataSnapshot) {
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        val admin = AdminReceiver.component(c)

        val rSnap = snap.child("restrictions")
        if (rSnap.exists()) {
            val active = dpm.getUserRestrictions(admin)
            for ((key, _) in Restrictions.all) {
                if (!rSnap.hasChild(key)) continue
                val want = rSnap.child(key).getValue(Boolean::class.java) == true
                val has = active.getBoolean(key)
                if (want && !has) dpm.addUserRestriction(admin, key)
                else if (!want && has) dpm.clearUserRestriction(admin, key)
            }
        }

        val frp = snap.child("frp")
        if (frp.exists()) {
            val enabled = frp.child("enabled").getValue(Boolean::class.java) == true
            val accounts = frp.child("accounts").children
                .mapNotNull { it.getValue(String::class.java) }
                .filter { it.isNotBlank() }
            try {
                if (enabled && accounts.isNotEmpty()) {
                    dpm.setFactoryResetProtectionPolicy(
                        admin,
                        FactoryResetProtectionPolicy.Builder()
                            .setFactoryResetProtectionAccounts(accounts)
                            .setFactoryResetProtectionEnabled(true)
                            .build()
                    )
                } else {
                    dpm.setFactoryResetProtectionPolicy(admin, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "frp apply failed: ${e.message}")
            }
        }

        heartbeat(c)
    }

    // ---------- one-shot commands ----------

    private fun handleCommand(c: Context, s: DataSnapshot) {
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        val admin = AdminReceiver.component(c)
        val type = s.child("type").getValue(String::class.java)
        try {
            when (type) {
                "lock" -> dpm.lockNow()
                "reboot" -> try { dpm.reboot(admin) } catch (e: Exception) { Log.w(TAG, "reboot: ${e.message}") }
                "hideApp" -> s.child("pkg").getValue(String::class.java)?.let { BootSafe.setHidden(c, it, true) }
                "showApp" -> s.child("pkg").getValue(String::class.java)?.let { BootSafe.setHidden(c, it, false) }
                "setPin" -> {
                    val hash = s.child("pinHash").getValue(String::class.java)
                    val plain = s.child("pin").getValue(String::class.java)
                    when {
                        !hash.isNullOrBlank() -> Pin.setHash(c, hash)
                        !plain.isNullOrBlank() -> Pin.set(c, plain)
                    }
                }
                "clearPin" -> Pin.clear(c)
                "refreshApps" -> ref?.let { publishApps(c, it) }
                "sync" -> {}
                "wipe" -> if (s.child("confirm").getValue(Boolean::class.java) == true) {
                    @Suppress("DEPRECATION") dpm.wipeData(0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "command '$type' failed", e)
        } finally {
            s.ref.removeValue()   // consume it so it never runs twice
            heartbeat(c)
        }
    }

    // ---------- status back to the dashboard ----------

    fun heartbeat(c: Context) {
        val r = ref ?: return
        Thread {
            try {
                val dpm = c.getSystemService(DevicePolicyManager::class.java)
                val admin = AdminReceiver.component(c)
                val active = dpm.getUserRestrictions(admin)
                val restr = Restrictions.all.associate { (k, _) -> k to active.getBoolean(k) }
                val hidden = hiddenPackages(c)
                val status = hashMapOf<String, Any?>(
                    "online" to true,
                    "lastSeen" to ServerValue.TIMESTAMP,
                    "deviceId" to DeviceId.get(c),
                    "model" to Build.MODEL,
                    "androidVersion" to Build.VERSION.RELEASE,
                    "battery" to batteryPct(c),
                    "deviceOwner" to dpm.isDeviceOwnerApp(c.packageName),
                    "pinSet" to Pin.isSet(c),
                    "appVersion" to appVersion(c),
                    "restrictions" to restr,
                    "hiddenApps" to hidden,
                    "frp" to frpStatus(dpm, admin)
                )
                r.child("status").updateChildren(status)
            } catch (e: Exception) {
                Log.w(TAG, "heartbeat failed", e)
            }
        }.start()
    }

    private fun frpStatus(dpm: DevicePolicyManager, admin: ComponentName): Map<String, Any?> = try {
        val p = dpm.getFactoryResetProtectionPolicy(admin)
        val accs = p?.factoryResetProtectionAccounts.orEmpty()
        mapOf(
            "supported" to true,
            "enabled" to (p?.isFactoryResetProtectionEnabled == true && accs.isNotEmpty()),
            "accounts" to accs
        )
    } catch (e: UnsupportedOperationException) {
        mapOf("supported" to false, "enabled" to false, "accounts" to emptyList<String>())
    } catch (e: Exception) {
        mapOf("supported" to true, "enabled" to false, "accounts" to emptyList<String>())
    }

    /** Launchable apps plus anything currently hidden, as [{pkg,label}] (values, so dots are fine). */
    private fun publishApps(c: Context, r: DatabaseReference) {
        Thread {
            try {
                val pm = c.packageManager
                val list = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    .asSequence()
                    .map { it.packageName }
                    .filter { pm.getLaunchIntentForPackage(it) != null || isHidden(c, it) }
                    .distinct()
                    .map { mapOf("pkg" to it, "label" to labelOf(c, it)) }
                    .sortedBy { (it["label"] as String).lowercase() }
                    .toList()
                r.child("apps").setValue(list)
            } catch (e: Exception) {
                Log.w(TAG, "publishApps failed", e)
            }
        }.start()
    }

    // ---------- helpers ----------

    private fun isHidden(c: Context, pkg: String): Boolean = try {
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        dpm.isApplicationHidden(AdminReceiver.component(c), pkg)
    } catch (e: Exception) { false }

    private fun hiddenPackages(c: Context): List<String> {
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        val admin = AdminReceiver.component(c)
        return c.packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .map { it.packageName }
            .filter { dpm.isApplicationHidden(admin, it) }
    }

    private fun labelOf(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .loadLabel(c.packageManager).toString()
    } catch (e: Exception) { pkg }

    private fun batteryPct(c: Context): Int = try {
        val bm = c.getSystemService(BatteryManager::class.java)
        val fromMgr = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (fromMgr in 0..100) fromMgr else {
            val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) level * 100 / scale else -1
        }
    } catch (e: Exception) { -1 }

    private fun appVersion(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }
}
