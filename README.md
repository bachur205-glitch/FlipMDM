# Flip MDM (for TCL Flip 2)

A device-owner app: PIN lock, restriction toggles, app hiding, factory-reset
protection tied to a Google account, and optional remote control from a web
dashboard. Works with the D-pad and number keys.

Remote control and the dashboard are **optional** — leave Firebase unset and the
phone runs fully local, exactly as before. See sections 5 and 6 to turn them on.

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
  (hardware buttons) can't be *blocked* — but **factory-reset protection (section 6)**
  makes the phone useless after such a reset until someone signs in to your Google
  account. That's the real protection against a recovery wipe.
- Once it's final, remove `android:testOnly="true"` from AndroidManifest.xml, rebuild,
  and set it up again. Without testOnly it can only be removed from inside the app (or by a full reset).

## 5. Remote control (web dashboard) — optional

Control the phone from a web page: see its status, flip restrictions, hide/show
apps, lock it, reboot it, set reset protection, or wipe it — from anywhere. It
uses **Firebase Realtime Database** (free tier, no server to run).

### a. Create the Firebase project
1. [console.firebase.google.com](https://console.firebase.google.com) > Add project.
2. **Build > Realtime Database > Create database.** Note the URL at the top,
   e.g. `https://flipmdm-xxxx-default-rtdb.firebaseio.com`.
3. **Build > Authentication > Get started**, and enable **Anonymous** (for the
   phone) and **Google** (for you, in the dashboard).
4. **Rules tab:** paste the contents of [`database.rules.json`](database.rules.json)
   and Publish.

### b. Point the phone at it
1. Firebase console > **Project settings > Your apps > Add app > Android**.
   Package name: `com.moshe.flipmdm`. Download the **`google-services.json`** it gives you.
2. Replace `app/google-services.json` with that file — either commit it, or (cleaner)
   keep it out of the repo and paste its contents into a GitHub Actions secret named
   **`GOOGLE_SERVICES_JSON`** (the build workflow picks it up automatically).
3. Put the Realtime Database URL from step a‑2 into
   [`app/src/main/res/values/flipmdm.xml`](app/src/main/res/values/flipmdm.xml),
   in `mdm_database_url`.
4. Rebuild and reinstall. The app's **Remote control** section now shows a **Device ID**.

### c. Open the dashboard
1. Edit [`dashboard/firebase-config.js`](dashboard/firebase-config.js) with your web
   app config (Project settings > Your apps > Web app > Config).
2. Host the `dashboard/` folder. Easiest: this repo's workflow already publishes it to
   **GitHub Pages** — enable it once under repo **Settings > Pages > Source =
   "GitHub Actions"**. Or run it locally with any static server.
3. Open the page, **Sign in with Google**, and click **Claim this dashboard** the first
   time (that makes your account the owner in the database rules).
4. The phone appears within a minute. Toggles write a "desired state"; one-shot buttons
   (lock, reboot, wipe) are sent as commands and run once.

**Notes**
- The Firebase web config and database URL aren't secrets — access is gated by Google
  sign-in and the security rules, not by hiding them.
- If **"Block adding accounts"** is on, that doesn't affect the phone's own anonymous
  Firebase sign-in. But if you later **"Remove MDM completely"**, remote control stops too.
- Commands need the phone online (it keeps a live connection via the foreground
  "Flip MDM is active" notification). Offline commands are delivered when it reconnects.

## 6. Factory-reset protection (FRP) with a Google account

This is what stops a recovery-mode wipe from giving someone a clean phone: after
**any** reset, setup won't finish until your Google account is signed in.

On the phone, open **Flip MDM > Recovery-mode reset protection (FRP)**:
- **Easiest:** tap **Use: your@gmail.com** under "Lock to a Google account on this
  phone." The app looks up the account ID that FRP needs and turns protection on.
- **If that fails** (some builds don't expose the ID): get the ID yourself — sign in
  to the account, open the People API
  [`people/me`](https://developers.google.com/people/api/rest/v1/people/get) explorer,
  and copy the number after `people/` in the response. Paste it into the ID box and
  **Turn on with this ID**.
- You can also set/clear FRP remotely from the dashboard (section 5).

**Notes**
- Use an account you control and won't lose access to. If you forget it, a reset leaves
  the phone locked to it — that's the whole point, but it cuts both ways.
- The account must stay a Google account you can sign in to at setup time.
- "Turn off reset protection" (in the app or dashboard) clears it cleanly before you
  sell or hand off the phone.
