package com.moshe.flipmdm

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserManager
import android.text.InputType
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
        UserManager.DISALLOW_FACTORY_RESET to "Block factory reset (in Settings)",
        UserManager.DISALLOW_SAFE_BOOT to "Block safe mode",
        UserManager.DISALLOW_ADD_USER to "Block adding users",
        UserManager.DISALLOW_MODIFY_ACCOUNTS to "Block adding accounts",
        UserManager.DISALLOW_CONFIG_DATE_TIME to "Block changing date/time",
        UserManager.DISALLOW_DEBUGGING_FEATURES to "Block USB debugging (adb)",
    )

    /** Packages that would break the phone if hidden. */
    private val protectedPackages = mutableSetOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.providers.telephony",
        "com.android.shell",
    )

    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DevicePolicyManager::class.java)
        admin = AdminReceiver.component(this)

        // Never allow hiding the home screen app.
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
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

    private fun note(t: String) = root.addView(TextView(this).apply {
        text = t; textSize = 13f
    })

    private fun button(t: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = t; setOnClickListener { onClick() }
    })

    private fun pinField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }.also { root.addView(it) }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

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

        title("Apps")
        button("Hide / show apps") { showApps(showAll = false) }

        title("PIN")
        button("Change PIN") { showSetPin() }

        title("Remove")
        button("Remove MDM completely") { confirmRelease() }
    }

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
        packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .forEach {
                if (dpm.isApplicationHidden(admin, it.packageName)) {
                    dpm.setApplicationHidden(admin, it.packageName, false)
                }
            }
        dpm.setUninstallBlocked(admin, packageName, false)
        Pin.clear(this)
        dpm.clearDeviceOwnerApp(packageName)
        toast("MDM removed. You can uninstall this app now.")
        finish()
    }
}
