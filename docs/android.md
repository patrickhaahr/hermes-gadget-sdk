# Run an Android gadget

The Android client turns a spare phone into a gadget. It runs the same device core as the ESP32 firmware, loaded into an app through the NDK. It uses the phone's microphone, speaker, and screen, and connects to the Gadget plugin on your Hermes host over Wi-Fi.

This port is experimental. CI builds the app and runs the device core through the app's native bridge on the build machine. No physical phone report is recorded yet.

For now you hold the screen to talk. Hands-free wake-word listening is not included yet.

## What you need

- A phone running Android 11 or newer.
- A Hermes host with the Gadget plugin, set up as in [Connect Hermes](connect-hermes.md). Voice needs speech recognition and text-to-speech configured there.
- A computer with `adb` and a USB cable.
- For a dedicated device: a phone with no accounts on it.

## Install

1. Download `app-debug.apk` from the `android-apk` artifact of a successful `main` [CI run](https://github.com/Adolanium/hermes-gadget-sdk/actions/workflows/ci.yml) and extract the ZIP. To build it yourself, see [Build from source](#build-from-source).
2. On the phone, turn on **Developer options** and **USB debugging**. Connect the phone and accept the prompt.
3. Install the app:

   ```bash
   adb install -r app-debug.apk
   ```

## Make it a dedicated device

Use this when the phone does nothing else. As the device owner, the app becomes the home screen, stays pinned on screen, keeps its microphone permission, and starts again after a reboot. Skip this section to run it as an ordinary app.

Android allows a device owner only on a phone with no accounts. Remove every account in **Settings → Accounts** first. Also set **Settings → Security → Screen lock** to **None**: Android asks for a PIN, pattern or password after every reboot, before any app can start, so a phone with a screen lock waits at the lock screen instead of starting the gadget. Then run:

```bash
adb shell dpm set-device-owner io.github.adolanium.hermesgadget/.AdminReceiver
```

The command fails with a message about accounts or existing users if the phone isn't ready. The other apps stay out of reach in this mode. To reach Android's settings (for example, to turn wireless debugging back on after a reboot), open the [settings screen](#change-the-settings) and choose **Open Android settings**. To take the phone out of this mode, choose **Leave kiosk mode for now** or **Stop being the device owner** there.

## Connect to Hermes

Run `hermes gadget info` on the Hermes host for the gadget URL, then send it to the phone:

```bash
adb shell am start -n io.github.adolanium.hermesgadget/.AdbSetup \
    --es server ws://192.168.1.20:8765/gadget --es name "'Robot head'"
```

The phone's shell splits the command again, so a name with spaces needs both sets of quotes. Add `--es token SECRET` if the host sets `GADGET_ACCESS_TOKEN`. You can also open the app and type the same values on its settings screen.

The phone shows a pairing code. Approve it on the Hermes host with `hermes gadget pair`. Over Tailscale, use the host's tailnet name or address in the URL. For other networks, see [Connect from another network](tailscale-funnel.md).

To try the app without Hermes, run the development server on your computer and forward its port over the USB cable:

```bash
hermes-gadget devserver --pairing
adb reverse tcp:8765 tcp:8765
adb shell am start -n io.github.adolanium.hermesgadget/.AdbSetup --es server ws://127.0.0.1:8765/gadget
```

## Use it

- **Talk:** hold the screen while you speak, then release to send. Swipe down to cancel. The core's other touch gestures work as on [touch boards](using-gadget.md).
- **Microphone switch:** if Android's microphone privacy switch is off, recordings are silent and Android asks to unblock the microphone the first time you talk. Leave the switch on for a dedicated device.
- **Listen:** replies play through the phone's speaker. Text and status show on screen.
- **Screen off:** the gadget keeps its connection and microphone with the screen off. Once the core's screen timeout puts the display to sleep, Android may turn the screen off. A touch wakes it. The timeout is off by default; see [Console commands](#console-commands).
- **Background running:** the app runs as a foreground service with a notification. On phones that close background apps aggressively, open the settings screen and choose **Allow running in the background**.

## Change the settings

Hold **volume up** for about three seconds on the gadget screen to open the settings, or run:

```bash
adb shell am start -n io.github.adolanium.hermesgadget/.AdbSetup
```

The settings screen also opens Android's developer options with **Open Android settings**, even in kiosk mode, and warns when a screen lock would stop the gadget from starting after a reboot. It shows the core's live status (phase, pairing, and server) for troubleshooting.

The device key stays in the app's private storage and is excluded from backups and device transfers. Uninstalling the app or clearing its data creates a new device, which must be paired again. Run `hermes gadget forget <device_id>` on the host for the old one.

## Console commands

The core's console commands that the other ports take over USB serial, such as `status` or `set screen_timeout 60`, go through adb. Each answer appears in the log:

```bash
adb shell am start -n io.github.adolanium.hermesgadget/.AdbSetup --es console "'set screen_timeout 60'"
adb logcat -s HermesGadget
```

## Build from source

Install JDK 17 and the Android SDK with platform 35, build-tools 35.0.0, NDK 27.0.12077973 and CMake 3.22.1. Then build at the repository root:

```bash
cd android
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The unit tests also build the app's native bridge for your computer, so they need CMake and a C++17 compiler. They drive the device core through the protocol handshake, replies, and speaker audio.

## Limits

- Wake-word listening and camera support are not included yet.
- The display is the core's renderer at 360 pixels wide, scaled up with square pixels. The app runs in portrait.
- Android delivers no updates over the gadget connection. Install new versions with `adb install -r`.
- See the [verification table](hardware-validation.md) for what has been tested on a phone.
