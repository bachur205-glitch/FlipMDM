package com.moshe.flipmdm

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.text.InputType
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
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

    private val restrictions = listOf(
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

    /** Packages that would break the phone if hidden. */
    private val protectedPackages = mutableSetOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.providers.telephony",
        "com.android.shell",
    )

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

    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DevicePolicyManager::class.java)
        admin = AdminReceiver.component(this)

        // Never allow hiding the home screen app or the dialer.
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
                }
            })
        }

        browserSection()
        resetProtectionSection()

        title("Secret dialer codes")
        note("Find the apps that respond to *#*#...#*#* codes and hide the risky ones.")
        button("Scan secret codes") { showSecretCodes() }

        title("Apps")
        button("Hide / show apps") { showApps(showAll = false) }

        title("PIN")
        button("Change PIN") { showSetPin() }

        title("Remove")
        button("Remove MDM completely") { confirmRelease() }
    }

    // ---------- in-app browsers ----------

    private fun browserEnginePackages(): List<String> {
        val all = browserEngineCandidates.toMutableSet()
        WebView.getCurrentWebViewPackage()?.packageName?.let { all.add(it) }
        return all.filter { isInstalled(it) }
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
                val failed = pkgs.filter { !dpm.setApplicationHidden(admin, it, on) }
                if (failed.isNotEmpty()) toast("Couldn't change: ${failed.joinToString()}")
            }
        })
        note(
            "Packages: ${pkgs.joinToString()}\n" +
            "Apps that rely on an in-app browser may show errors or close. " +
            "Test the apps you need after turning this on."
        )
    }

    // ---------- factory reset protection (recovery mode) ----------

    private fun resetProtectionSection() {
        title("Recovery-mode reset protection")
        val supported = try {
            dpm.isFactoryResetProtectionPolicySupported
        } catch (e: Exception) {
            false
        }
        if (!supported) {
            note(
                "This phone doesn't support reset protection, so a reset from " +
                "recovery mode can't be locked."
            )
            return
        }

        val current = try { dpm.getFactoryResetProtectionPolicy(admin) } catch (e: Exception) { null }
        val accounts = current?.factoryResetProtectionAccounts.orEmpty()
        val isOn = current?.isFactoryResetProtectionEnabled == true && accounts.isNotEmpty()
        note(if (isOn) "Status: ON (account ID ${accounts.joinToString()})" else "Status: OFF")
        note(
            "When this is on, after ANY reset (including from recovery mode) the phone " +
            "won't finish setup until someone signs in to this Google account."
        )

        val field = EditText(this).apply {
            hint = "Google account ID (numbers only)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }.also { root.addView(it) }

        button("Turn on reset protection") {
            val id = field.text.toString().trim()
            if (id.isEmpty()) {
                toast("Enter the Google account ID first")
                return@button
            }
            try {
                dpm.setFactoryResetProtectionPolicy(
                    admin,
                    FactoryResetProtectionPolicy.Builder()
                        .setFactoryResetProtectionAccounts(listOf(id))
                        .setFactoryResetProtectionEnabled(true)
                        .build()
                )
                toast("Reset protection is on")
            } catch (e: Exception) {
                toast("Failed: ${e.message}")
            }
            showDashboard()
        }
        if (isOn) {
            button("Turn off reset protection") {
                try { dpm.setFactoryResetProtectionPolicy(admin, null) } catch (_: Exception) {}
                showDashboard()
            }
        }
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
            "Checked = hidden (all its codes stop working). Look for names like " +
            "Engineer Mode, Factory Test, MMI or Diagnostics. If you hide the app " +
            "behind *#*#33284#*#*, that code will stop working too."
        for ((pkg, codes) in results) {
            val line = "${labelOf(pkg)}\n$pkg\n" +
                codes.sorted().joinToString(", ") { "*#*#$it#*#*" }
            if (pkg in protectedPackages) {
                note("$line\n(Can't hide: the phone needs this app)")
                continue
            }
            root.addView(CheckBox(this).apply {
                text = line
                textSize = 13f
                isChecked = dpm.isApplicationHidden(admin, pkg)
                setOnCheckedChangeListener { _, on ->
                    if (!dpm.setApplicationHidden(admin, pkg, on)) toast("Couldn't change $pkg")
                }
            })
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
                pkg !in protectedPackages && (
                    showAll ||
                    dpm.isApplicationHidden(admin, pkg) ||
                    pm.getLaunchIntentForPackage(pkg) != null
                )
            }
            .sortedBy { it.second.lowercase() }

        for ((pkg, label) in apps) {
            root.addView(CheckBox(this).apply {
                text = "$label\n$pkg"
                textSize = 13f
                isChecked = dpm.isApplicationHidden(admin, pkg)
                setOnCheckedChangeListener { _, on ->
                    if (!dpm.setApplicationHidden(admin, pkg, on)) toast("Couldn't change $label")
                }
            })
        }
    }

    // ---------- removal ----------

    private fun confirmRelease() {
        AlertDialog.Builder(this)
            .setTitle("Remove MDM?")
            .setMessage(
                "This clears every restriction, un-hides all apps, turns off reset " +
                "protection, and removes device-owner control. To set it up again " +
                "you'll need the adb command."
            )
            .setPositiveButton("Remove") { _, _ -> release() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun release() {
        for ((key, _) in restrictions) dpm.clearUserRestriction(admin, key)
        packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .forEach {
                if (dpm.isApplicationHidden(admin, it.packageName)) {
                    dpm.setApplicationHidden(admin, it.packageName, false)
                }
            }
        try { dpm.setFactoryResetProtectionPolicy(admin, null) } catch (_: Exception) {}
        dpm.setUninstallBlocked(admin, packageName, false)
        Pin.clear(this)
        dpm.clearDeviceOwnerApp(packageName)
        toast("MDM removed. You can uninstall this app now.")
        finish()
    }
}
