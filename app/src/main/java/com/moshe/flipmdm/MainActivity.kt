package com.moshe.flipmdm

import android.accounts.AccountManager
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Whole UI is built in code and uses only standard widgets, so it works
 * with the Flip 2's D-pad and number keys (no touchscreen needed).
 */
class MainActivity : Activity() {

    // Shared with the remote client and the web dashboard (see Restrictions.kt).
    private val restrictions get() = Restrictions.all

    /** Packages that provide in-app browsers (WebView) and Custom Tabs (Chrome). */
    private val browserEngineCandidates = listOf(
        "com.google.android.webview",
        "com.android.webview",
        "com.google.android.webview.beta",
        "com.google.android.webview.dev",
        "com.google.android.webview.canary",
        "com.google.android.trichromelibrary",
        "com.android.chrome",
    )

    /** Longer dialer codes to check on top of every 1–4 digit code. */
    private val extraSecretCodes = listOf(
        "33284", "83781", "66336", "46364", "3646633", "2846579",
        "12580369", "22558463", "7378423", "232338", "232339",
    )

    /** Extra packages that must never be hidden (home screen, dialer, this app). */
    private val protectedPackages = mutableSetOf<String>()

    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DevicePolicyManager::class.java)
        admin = AdminReceiver.component(this)

        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName?.let { protectedPackages.add(it) }
        packageManager.resolveActivity(
            Intent(Intent.ACTION_DIAL), 0
        )?.activityInfo?.packageName?.let { protectedPackages.add(it) }
        protectedPackages.add(packageName)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        if (dpm.isDeviceOwnerApp(packageName)) GuardService.start(this)

        when {
            !dpm.isDeviceOwnerApp(packageName) -> showNotDeviceOwner()
            !Pin.isSet(this) -> showSetPin()
            else -> showPinPrompt()
        }
    }

    // Ask for the PIN again every time the app leaves the screen.
    override fun onStop() {
        super.onStop()
        finish()
    }

    // ---------- small UI helpers ----------

    private fun clear() = root.removeAllViews()

    private fun title(t: String) = root.addView(TextView(this).apply {
        text = t; textSize = 18f; setPadding(0, 16, 0, 8)
    })

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 13f
    }.also { root.addView(it) }

    private fun button(t: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = t; setOnClickListener { onClick() }
    })

    private fun pinField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }.also { root.addView(it) }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun isInstalled(pkg: String) = try {
        packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun labelOf(pkg: String) = try {
        packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .loadLabel(packageManager).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun isProtected(pkg: String) =
        pkg in protectedPackages || BootSafe.isCore(this, pkg)

    private fun hiddenPackages(): List<String> =
        packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .map { it.packageName }
            .filter { dpm.isApplicationHidden(admin, it) }

    /** A checkbox that hides/shows one app, with a warning before hiding a system app. */
    private fun hideCheckBox(pkg: String, text: String): CheckBox {
        val box = CheckBox(this)
        box.text = text
        box.textSize = 13f
        box.isChecked = dpm.isApplicationHidden(admin, pkg)

        val listener = object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(b: CompoundButton, on: Boolean) {
                val l = this
                if (on && BootSafe.isSystem(this@MainActivity, pkg)) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Hide a system app?")
                        .setMessage(
                            "${labelOf(pkg)} came with the phone. Hiding it can break other " +
                            "parts of the phone. If anything stops working, un-hide it. " +
                            "It will be shown briefly during each restart so the phone can start."
                        )
                        .setPositiveButton("Hide") { _, _ -> applyHide(b, pkg, true, l) }
                        .setNegativeButton("Cancel") { _, _ -> setSilently(b, false, l) }
                        .setOnCancelListener { setSilently(b, false, l) }
                        .show()
                } else {
                    applyHide(b, pkg, on, l)
                }
            }
        }
        box.setOnCheckedChangeListener(listener)
        return box
    }

    private fun setSilently(
        b: CompoundButton, value: Boolean, l: CompoundButton.OnCheckedChangeListener
    ) {
        b.setOnCheckedChangeListener(null)
        b.isChecked = value
        b.setOnCheckedChangeListener(l)
    }

    private fun applyHide(
        b: CompoundButton, pkg: String, hide: Boolean, l: CompoundButton.OnCheckedChangeListener
    ) {
        if (!BootSafe.setHidden(this, pkg, hide)) {
            toast("Couldn't change ${labelOf(pkg)}")
            setSilently(b, !hide, l)
        }
    }

    // ---------- screens ----------

    private fun showNotDeviceOwner() {
        clear()
        title("Not set up yet")
        note(
            "This app is not the device owner. Remove all accounts from the phone, " +
            "connect it to a computer, and run:\n\n" +
            "adb shell dpm set-device-owner com.moshe.flipmdm/.AdminReceiver"
        )
    }

    private fun showSetPin() {
        clear()
        title("Create a PIN")
        note("You'll need this PIN to change any setting. Don't lose it.")
        val p1 = pinField("New PIN (4+ digits)")
        val p2 = pinField("Repeat PIN")
        button("Save PIN") {
            val a = p1.text.toString()
            val b = p2.text.toString()
            when {
                a.length < 4 -> toast("PIN must be at least 4 digits")
                a != b -> toast("PINs don't match")
                else -> { Pin.set(this, a); toast("PIN saved"); showDashboard() }
            }
        }
        p1.requestFocus()
    }

    private fun showPinPrompt() {
        clear()
        title("Enter PIN")
        val p = pinField("PIN")
        button("Unlock") {
            if (Pin.check(this, p.text.toString())) showDashboard()
            else { p.setText(""); toast("Wrong PIN") }
        }
        p.requestFocus()
    }

    private fun showDashboard() {
        clear()
        // Protect this app from being uninstalled.
        dpm.setUninstallBlocked(admin, packageName, true)

        title("Restrictions")
        val active = dpm.getUserRestrictions(admin)
        for ((key, label) in restrictions) {
            root.addView(CheckBox(this).apply {
                text = label
                isChecked = active.getBoolean(key)
                setOnCheckedChangeListener { _, on ->
                    if (on) dpm.addUserRestriction(admin, key)
                    else dpm.clearUserRestriction(admin, key)
                    Remote.heartbeat(this@MainActivity)
                }
            })
        }

        browserSection()
        resetProtectionSection()
        remoteSection()

        title("Hidden right now")
        val hidden = hiddenPackages()
        note(
            if (hidden.isEmpty()) "Nothing is hidden."
            else hidden.map { labelOf(it) }.sortedBy { it.lowercase() }.joinToString("\n")
        )

        title("Apps")
        button("Hide / show apps") { showApps(showAll = false) }

        title("Secret dialer codes")
        note("Find the apps that respond to *#*#...#*#* codes and hide the risky ones.")
        button("Scan secret codes") { showSecretCodes() }

        title("PIN")
        button("Change PIN") { showSetPin() }

        title("Remove")
        button("Remove MDM completely") { confirmRelease() }
    }

    // ---------- in-app browsers ----------

    private fun browserEnginePackages(): List<String> {
        val all = browserEngineCandidates.toMutableSet()
        try {
            WebView.getCurrentWebViewPackage()?.packageName?.let { all.add(it) }
        } catch (_: Exception) {}
        return all.filter { isInstalled(it) && !isProtected(it) }
    }

    private fun browserSection() {
        title("In-app browsers")
        val pkgs = browserEnginePackages()
        if (pkgs.isEmpty()) {
            note("No WebView or Chrome package found on this phone.")
            return
        }
        root.addView(CheckBox(this).apply {
            text = "Block in-app browsers (hides WebView + Chrome)"
            isChecked = pkgs.all { dpm.isApplicationHidden(admin, it) }
            setOnCheckedChangeListener { _, on ->
                val failed = pkgs.filter { !BootSafe.setHidden(this@MainActivity, it, on) }
                if (failed.isNotEmpty()) toast("Couldn't change: ${failed.joinToString()}")
            }
        })
        note(
            "Packages: ${pkgs.joinToString()}\n" +
            "These are shown briefly during each restart so the phone can start, " +
            "then hidden again. Apps that rely on an in-app browser may show errors."
        )
    }

    // ---------- factory reset protection (recovery mode), tied to a Google account ----------

    private fun resetProtectionSection() {
        title("Recovery-mode reset protection (FRP)")

        // As device owner we can grant ourselves account access for the picker below.
        try {
            dpm.setPermissionGrantState(
                admin, packageName, "android.permission.GET_ACCOUNTS",
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (_: Exception) {}

        // Phones without FRP support throw UnsupportedOperationException.
        val current = try {
            dpm.getFactoryResetProtectionPolicy(admin)
        } catch (e: UnsupportedOperationException) {
            note(
                "This phone doesn't support reset protection, so a reset from " +
                "recovery mode can't be locked to an account."
            )
            return
        } catch (e: Exception) {
            null
        }
        val accounts = current?.factoryResetProtectionAccounts.orEmpty()
        val isOn = current?.isFactoryResetProtectionEnabled == true && accounts.isNotEmpty()
        val email = frpEmail()
        note(
            if (isOn) "Status: ON" + (email?.let { " ($it)" } ?: "") +
                "\nAccount ID(s): ${accounts.joinToString()}"
            else "Status: OFF"
        )
        note(
            "When this is on, after ANY reset (including from recovery mode) the phone " +
            "won't finish setup until someone signs in to this Google account."
        )

        // Easiest path: pick a Google account already on the phone and let the app
        // look up the ID that FRP needs.
        val googleAccounts = try {
            AccountManager.get(this).getAccountsByType("com.google").map { it.name }
        } catch (e: Exception) {
            emptyList()
        }
        if (googleAccounts.isEmpty()) {
            note("No Google account found on this phone. Add one in Settings, or enter an ID below.")
        } else {
            note("Lock to a Google account on this phone:")
            for (name in googleAccounts) {
                button("Use: $name") { resolveAndEnableFrp(name) }
            }
        }

        // Fallback: type the numeric account ID in by hand.
        val field = EditText(this).apply {
            hint = "…or paste the account ID (numbers)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }.also { root.addView(it) }
        button("Turn on with this ID") {
            val id = field.text.toString().trim()
            if (id.isEmpty()) toast("Enter the account ID first")
            else enableFrp(listOf(id), null)
        }
        note(
            "No ID? Sign in to the account at myaccount.google.com, open " +
            "developers.google.com/people/api/rest/v1/people/get, call people/me, and copy " +
            "the number after \"people/\" in the response."
        )

        if (isOn) {
            button("Turn off reset protection") {
                try { dpm.setFactoryResetProtectionPolicy(admin, null) } catch (_: Exception) {}
                saveFrpEmail(null)
                Remote.heartbeat(this)
                showDashboard()
            }
        }
    }

    private fun resolveAndEnableFrp(email: String) {
        toast("Looking up the account ID…")
        Thread {
            @Suppress("DEPRECATION")
            val id = try {
                com.google.android.gms.auth.GoogleAuthUtil.getAccountId(this, email)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (id.isNullOrBlank())
                    toast("Couldn't get the ID automatically. Enter it by hand below.")
                else
                    enableFrp(listOf(id), email)
            }
        }.start()
    }

    private fun enableFrp(ids: List<String>, email: String?) {
        try {
            dpm.setFactoryResetProtectionPolicy(
                admin,
                FactoryResetProtectionPolicy.Builder()
                    .setFactoryResetProtectionAccounts(ids)
                    .setFactoryResetProtectionEnabled(true)
                    .build()
            )
            saveFrpEmail(email)
            toast("Reset protection is on")
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
        Remote.heartbeat(this)
        showDashboard()
    }

    private fun saveFrpEmail(e: String?) =
        getSharedPreferences("mdm", Context.MODE_PRIVATE).edit().apply {
            if (e == null) remove("frp_email") else putString("frp_email", e)
        }.apply()

    private fun frpEmail(): String? =
        getSharedPreferences("mdm", Context.MODE_PRIVATE).getString("frp_email", null)

    // ---------- remote web dashboard ----------

    private fun remoteSection() {
        title("Remote control")
        if (!Remote.isConfigured(this)) {
            note(
                "Off. To control this phone from the web dashboard, set up Firebase and put " +
                "your database URL in app/src/main/res/values/flipmdm.xml, then rebuild. " +
                "See the README."
            )
            return
        }
        note(
            "Device ID: ${Remote.deviceId(this)}\n" +
            "This is the phone's row in the dashboard. Open the dashboard, sign in with " +
            "Google, and this phone appears within a minute."
        )
        button("Send status now") { Remote.heartbeat(this); toast("Status sent") }
    }

    // ---------- secret dialer codes ----------

    private fun showSecretCodes() {
        clear()
        button("< Back") { showDashboard() }
        title("Secret-code apps")
        val status = note("Scanning codes... this can take a minute.")

        val actions = mutableListOf("android.provider.Telephony.SECRET_CODE")
        if (Build.VERSION.SDK_INT >= 31) actions.add("android.telephony.action.SECRET_CODE")

        val hosts = mutableListOf<String>()
        for (len in 1..4) {
            var max = 1
            repeat(len) { max *= 10 }
            for (n in 0 until max) hosts.add(n.toString().padStart(len, '0'))
        }
        hosts.addAll(extraSecretCodes)

        Thread {
            val pm = packageManager
            val found = sortedMapOf<String, MutableSet<String>>()
            hosts.forEachIndexed { i, host ->
                for (action in actions) {
                    val intent = Intent(action, Uri.parse("android_secret_code://$host"))
                    for (r in pm.queryBroadcastReceivers(intent, PackageManager.MATCH_ALL)) {
                        found.getOrPut(r.activityInfo.packageName) { mutableSetOf() }.add(host)
                    }
                }
                if (i % 500 == 0) runOnUiThread { status.text = "Scanning... ${i * 100 / hosts.size}%" }
            }
            runOnUiThread { if (!isFinishing) showSecretCodeResults(status, found) }
        }.start()
    }

    private fun showSecretCodeResults(status: TextView, found: Map<String, Set<String>>) {
        val results = found.filterKeys { it != packageName }
        if (results.isEmpty()) {
            status.text = "No apps respond to secret codes (or they're already hidden)."
            return
        }
        status.text =
            "Checked = hidden (all its codes stop working). Core system apps can't be " +
            "hidden; for those, \"Block factory reset\" is what stops reset codes."
        for ((pkg, codes) in results) {
            val line = "${labelOf(pkg)}\n$pkg\n" +
                codes.sorted().joinToString(", ") { "*#*#$it#*#*" }
            if (isProtected(pkg)) {
                note("$line\n(Core system app — can't hide)")
            } else {
                root.addView(hideCheckBox(pkg, line))
            }
        }
    }

    // ---------- app hiding ----------

    private fun showApps(showAll: Boolean) {
        clear()
        button("< Back") { showDashboard() }
        title("Checked = hidden")
        button(if (showAll) "Show main apps only" else "Show all system packages") {
            showApps(!showAll)
        }

        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .map { it.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) ->
                !isProtected(pkg) && (
                    showAll ||
                    dpm.isApplicationHidden(admin, pkg) ||
                    pm.getLaunchIntentForPackage(pkg) != null
                )
            }
            .sortedBy { it.second.lowercase() }

        for ((pkg, label) in apps) root.addView(hideCheckBox(pkg, "$label\n$pkg"))
    }

    // ---------- removal ----------

    private fun confirmRelease() {
        AlertDialog.Builder(this)
            .setTitle("Remove MDM?")
            .setMessage(
                "This clears every restriction, un-hides all apps, and removes " +
                "device-owner control. To set it up again you'll need the adb command."
            )
            .setPositiveButton("Remove") { _, _ -> release() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun release() {
        for ((key, _) in restrictions) dpm.clearUserRestriction(admin, key)
        for (pkg in hiddenPackages()) dpm.setApplicationHidden(admin, pkg, false)
        try { dpm.setFactoryResetProtectionPolicy(admin, null) } catch (_: Exception) {}
        saveFrpEmail(null)
        BootSafe.clearAll(this)
        Remote.stop()
        stopService(Intent(this, GuardService::class.java))
        dpm.setUninstallBlocked(admin, packageName, false)
        Pin.clear(this)
        dpm.clearDeviceOwnerApp(packageName)
        toast("MDM removed. You can uninstall this app now.")
        finish()
    }
}
