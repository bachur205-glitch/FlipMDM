# Flip MDM (for TCL Flip 2)

A simple device-owner app: PIN lock, restriction toggles, and app hiding.
Works with the D-pad and number keys.

## 1. Build
1. Open this folder in Android Studio (File > Open). Let it sync. If it asks to
   create the Gradle wrapper, say yes.
2. Build > Build App Bundle(s) / APK(s) > Build APK(s).
   The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Prepare the phone
1. Back up anything you need.
2. Settings > Accounts: remove every account (Google, etc.). You can add them back afterward.
3. Turn on USB debugging: dial `*#*#33284#*#*`.
4. Plug into the computer and accept the "Allow USB debugging" prompt.

## 3. Install and activate
```
adb install -t app-debug.apk
adb shell dpm set-device-owner com.moshe.flipmdm/.AdminReceiver
```
(`-t` is needed because the app is marked testOnly.)

You should see: `Success: Device owner set to package ...`

Then open **Flip MDM** on the phone and create your PIN.

## 4. Removing it
- From the app: **Remove MDM completely**.
- From adb (only works while testOnly is on and debugging isn't blocked):
  `adb shell dpm remove-active-admin com.moshe.flipmdm/.AdminReceiver`

## Notes
- "Block installing apps" also blocks `adb install`. Turn it off before updating the app.
- If you turn on "Block USB debugging" and lose the PIN, adb can't save you.
  Test everything with debugging allowed first.
- "Block factory reset" covers the Settings menu only. A reset from recovery mode
  (hardware buttons) can't be blocked by any app.
- Once it's final, remove `android:testOnly="true"` from AndroidManifest.xml, rebuild,
  and set it up again. Without testOnly it can only be removed from inside the app (or by a full reset).
