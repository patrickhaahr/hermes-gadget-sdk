# Run an Android gadget

The Android client turns a spare phone into a gadget. It runs the same device core as the ESP32 firmware, loaded into an app through the NDK. It uses the phone's microphone, speaker, and screen, and connects to the Gadget plugin on your Hermes host over Wi-Fi.

This port is experimental. CI builds the app and runs the device core through the app's native bridge on the build machine. The [verification page](hardware-validation.md#oneplus-8t-wake-listening-report) records one physical phone report, for wake listening on a OnePlus 8T.

Say "Hey Hermes" and your request, or hold the screen to talk. Hermes uses its configured speech recognition and text-to-speech to answer in the phone's gadget conversation. See [Wake listening](#wake-listening).

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

## Wake listening

Wake listening runs on the phone. While waiting for the phrase, microphone audio goes only to the local detector. In **Hermes voice** mode, say "Hey Hermes" and then your request, without waiting for a sound. You can speak in one breath or pause briefly after the wake. The banner says **Listening for your request** and the face shows Listening. There is no wake sound to contaminate the recording.

The running microphone transfers to the recording without closing and reopening. Recording begins with the 80 ms chunk in which detection fires. Earlier chunks are discarded; there is no pre-roll. The request is buffered in memory on the phone until submission. Hermes may hear the tail of "Hermes". About one second of silence after speech sends one request through the same STT, gadget conversation and TTS path as hold-to-talk. No follow-up is recorded without another wake.

If no request is heard within about five seconds, the phone discards it locally and shows **Didn't hear anything**. Swipe down or turn Microphone off to discard a wake request without sending any audio. A recording lasts at most 30 seconds. The initial energy detector requires 200 ms of speech, with an RMS floor of 50 PCM16 units and a threshold of three times its adaptive noise floor. Its first 240 ms are retained for STT but excluded from speech detection to avoid counting the wake's tail. These are initial settings; see the [device report](hardware-validation.md#oneplus-8t-hermes-voice-checks) for measured checks and limitations.

A wake cannot answer an approval or question, replace a running turn, or record while disconnected, unpaired or holding the screen. The banner explains a refused wake. Wake listening pauses during Hermes's playback and resumes 500 ms after playback drains.

The chip at the top of the screen shows who has the microphone:

| Chip | Meaning |
|---|---|
| **Listening for "Hey Hermes"** | Wake listening is on. |
| **Recording** | Hold-to-talk or a wake request owns the microphone. |
| **Wake listening paused** | The gadget is speaking. Listening resumes half a second after it stops, so its own voice can't wake it. |
| **Microphone off** | Nothing uses the microphone. |
| **Wake listening unavailable** | The microphone is on, but listening can't run. The settings screen says why. Hold-to-talk still works. |

Only one of these uses the microphone at a time. Holding the screen takes the microphone from wake listening; letting go gives it back.

**Microphone off:** tap the chip to turn the microphone off, and tap it again to turn it back on. The settings screen has the same control. Microphone off stops wake listening and any recording in progress, and hold-to-talk shows "Microphone unavailable". The choice is saved before it takes effect, so it stays off after the app restarts, the phone reboots, or the app is updated, until you turn it on again. Over adb:

```bash
adb shell am start -n io.github.adolanium.hermesgadget/.AdbSetup --es microphone off   # or on
```

Wake listening keeps working with the screen off. The [8T report](hardware-validation.md#oneplus-8t-wake-listening-report) records the measured results. To follow it, read the log:

```bash
adb logcat -s HermesWake
```

It records each detection with its score, changes of the microphone's owner, and, for tuning, near misses and the loudest input level once a minute. It logs only these numbers, never audio.

**How it detects:** the app runs openWakeWord's melspectrogram and embedding models and Hermes Agent's `hey_hermes` classifier with [LiteRT](https://ai.google.dev/edge/litert). It uses the same streaming windows as [pyopen-wakeword](https://github.com/rhasspy/pyopen-wakeword), which Hermes Agent's desktop wake word uses. A detection needs a score of at least 0.8 in one 80 ms window. Hermes's desktop default, at least 0.6 in three consecutive windows, missed about half of real "Hey Hermes" attempts on the 8T: they scored as high, but for shorter runs. The detector then starts over, so one utterance gives one detection.

## Voice mode

The settings screen has **Voice mode**, next to the Microphone control:

- **Hermes voice** is the default: a wake records one request for Hermes's configured STT/TTS.
- **Live voice** is shown disabled until the subscription Live call integration is available. It cannot be selected, including over adb, and never silently falls back to Hermes voice.

The setting is saved before taking effect and survives app restarts, reboots and updates. Hold-to-talk is available in both modes. On a dedicated phone:

```bash
adb shell am start -n io.github.adolanium.hermesgadget/.AdbSetup --es voice_mode hermes
```

Using `--es voice_mode live` reports that Live voice is unavailable and leaves the saved choice unchanged.

## Change the settings

Hold **volume up** for about three seconds on the gadget screen to open the settings, or run:

```bash
adb shell am start -n io.github.adolanium.hermesgadget/.AdbSetup
```

The settings screen also opens Android's developer options with **Open Android settings**, even in kiosk mode, and warns when a screen lock would stop the gadget from starting after a reboot. It has the **Turn the microphone off** control. For troubleshooting, it shows the core's live status (phase, pairing, and server), the microphone's owner, the number of wake detections, and why wake listening is unavailable, if it is.

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

The build downloads the three wake models from their pinned releases and checks their SHA-256 hashes, so the first build needs network access. They aren't in the repository because openWakeWord's models are licensed for non-commercial use; see [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md#wake-word-models).

The unit tests also build the app's native bridge for your computer, so they need CMake and a C++17 compiler. They drive the device core through the protocol handshake, replies, speaker audio, and microphone ownership. The wake tests run the real models through the TensorFlow Lite C library from the pinned pyopen-wakeword wheel, which they download. That library exists for Linux x86-64 only; on other machines those tests are skipped. The coordinator tests also check exact uploaded PCM, local discards, refusals, ownership and re-arming through the real core. The tests check the recorded speech in `app/src/wakeFixtures` against the reference engine's scores for it. `android/tools/wake_fixtures.py` regenerates both.

To run the same fixtures through LiteRT on a phone:

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w io.github.adolanium.hermesgadget.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s HermesWakeTest
```

Don't use `./gradlew connectedAndroidTest` on a configured gadget. It uninstalls the app afterwards, which deletes the device key, so the gadget must pair again and its device-owner setup is lost. `am instrument` restarts the app and keeps its data.

## Limits

- Subscription Live voice calls and camera support are not included yet.
- Wake listening knows only "Hey Hermes", with English pronunciation. Its accuracy is measured on one phone, one speaker, and one room. The APK includes the wake models, which are licensed for non-commercial use only.
- The display is the core's renderer at 360 pixels wide, scaled up with square pixels. The app runs in portrait.
- Android delivers no updates over the gadget connection. Install new versions with `adb install -r`.
- See the [verification table](hardware-validation.md) for what has been tested on a phone.
For an opt-in acoustic check against your paired, configured Hermes host (this plays synthetic speech aloud and submits a real test request):

```bash
adb shell am instrument -w -e voiceAcoustic true -e screenOff true \
  -e class io.github.adolanium.hermesgadget.WakeRequestDeviceTest \
  io.github.adolanium.hermesgadget.test/androidx.test.runner.AndroidJUnitRunner
```

This uses the real phone microphone and loudspeaker. It is separate from human speech at 1–3 metres; see the [Hermes voice checks](hardware-validation.md#oneplus-8t-hermes-voice-checks).
