# Sound Alarm (Android)

Listens to the microphone and sets off an alarm when the sound gets too loud.
It keeps listening in the background (with a notification) even when the screen is off.

## Features
- Live loudness meter (0–100) with a trigger line
- Trigger level, "must stay loud for" time, and mic sensitivity
- Alarm sound: Siren, Beep, Buzzer or Chime, with volume and speed
- Test button, vibration, and a "Silence" button in the notification
- Settings are remembered

## Get the APK

### Option A: Android Studio (easiest)
1. Install Android Studio and choose **Open** on this folder. Let it sync.
2. Menu: **Build → Build APK(s)**.
3. Click "locate" in the popup. The file is `app/build/outputs/apk/debug/app-debug.apk`.

### Option B: Command line
Needs JDK 17 and the Android SDK (set `ANDROID_HOME`).

    ./gradlew assembleDebug

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Option C: GitHub (no installs)
1. Create a free GitHub repository and upload everything in this folder
   (including the hidden `.github` folder).
2. Open the **Actions** tab, run **Build APK**, wait a few minutes.
3. Download the `SoundAlarm-apk` artifact and unzip it to get `app-debug.apk`.

## Install on your phone
Copy `app-debug.apk` to the phone, open it, and allow "install unknown apps"
for your file manager or browser when asked.

## Tips
- Allow Microphone and Notifications when asked.
- For reliable background use, set the app's battery usage to **Unrestricted**
  (Settings → Apps → Sound Alarm → Battery).
- The 0–100 level is relative, not true decibels. Adjust the trigger level by ear.
- Alarm volume follows your phone's **alarm** volume as well as the in-app slider.
