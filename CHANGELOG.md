# Changelog

## Unreleased

### Hardware and setup

- Show a QR code on the Wi-Fi setup screen so a phone camera can join the gadget's temporary network without typing. The code encodes the network name and password with the standard `WIFI:` scheme. The instructions stay printed beside it, since the phone still opens the setup address and some phones cannot scan. Screens without room for a scannable code beside the instructions keep the text-only screen.
- Boards whose AXP2101 fuel gauge reports no valid estimate, such as the AMOLED-1.75C where it is never initialized and reads 0 on a full cell, now estimate the battery percentage from the cell voltage, so the low-battery reminder no longer sticks on a full battery. Contributed by MacroAnarchy.

### Firmware

- A connection no longer times out the moment it starts when drawing the screen takes a few milliseconds. `tick()` read the clock once, and a time stamped later in the same tick wrapped to a huge elapsed time, so the core closed the new socket and only connected after the one-second backoff. The tick's timeouts now count such a time as no time passed. Found on the Android client, whose full-screen flush crosses JNI.
- A console command that times out no longer leaves the app task writing its reply into freed memory. The request slot is shared by both tasks and freed by whichever finishes last, so a late reply is dropped instead of corrupting the stack. The slot is host-tested.
- The device refuses an over-the-air image built for another board, by reading the `HGBOARD=` tag as the image streams in, so a wrong image can no longer reach Wi-Fi and Hermes and pass the rollback check. The plugin's own check stays as the first line.
- A setting that cannot be saved is reported: `set` answers `@error could not save <key>`, a device key that cannot be saved is logged as an error, and a key longer than NVS allows is refused instead of silently cut.
- Display transfers no longer race their own DMA: the AMOLED-1.75/1.75C/1.8 and T-Display-S3 drivers now use the same band-by-band flush as the SPI panels, so a late or failed transfer pauses or stops drawing instead of refilling a buffer the DMA is still reading. The CrowPanel RGB path reports a failed flush or backlight change once instead of rebooting, and a display that fails to start leaves the gadget running without a screen (USB console and Wi-Fi still work) instead of rebooting in a loop. A touch task that cannot start is reported as a failed input.

### Android

- Console commands now run once through the Android JNI bridge, so `say` sends one message instead of two. Console and status replies are capped at 16 KiB including the terminating null byte.
- An experimental Android client in `android/` runs the device core on a phone through the NDK. It covers pairing, hold-to-talk on the touch screen, spoken replies and the face, with the screen on or off. It can also run as a dedicated device: as device owner it is the home screen, stays pinned and starts after a reboot. It is configured over adb or from its settings screen, which can also open Android's developer options while the kiosk is locked and warns when a screen lock would stop the gadget from starting after a reboot. CI builds the APK and drives the core through the app's JNI bridge. See [docs/android.md](docs/android.md).

### Linux

- The Raspberry Pi installer installs the release's pinned Python dependencies from a hash-checked lock file shipped in the package, wheels only, so a Pi runs exactly what CI tested instead of whatever PyPI serves that day. The release workflow runs the container install test before publishing.
- The Raspberry Pi installer checks that a new release answers on its control socket before calling the update done, and goes back to the previous release when it does not, matching the firmware's rollback. `sudo hermes-gadget-device rollback` swaps the current and previous releases by hand. A dependency installation that fails is removed instead of blocking the next attempt, the newest three releases are kept, and a release that keeps crashing at startup stops being restarted after five tries.

### Hermes plugin

- Spoken replies stay paced after the text-to-speech producer pauses. A sign error in the hub's stall handling let the rest of a reply go out in one burst after the first pause, which could overflow a device's speaker buffer. A fake-clock test now holds every frame within the playback lead, before and after a stall.
- Enrollment by unapproved devices is bounded: at most 16 may wait to be paired, 4 per network address, and an unapproved record expires an hour after the device's last contact. `hermes gadget pair --yes` approves blindly only when one device is waiting; `hermes gadget pair <device>` picks one. `hermes gadget devices` marks devices still waiting to pair.
- `hermes gadget forget` takes effect on a running gateway. The device store re-reads `devices.json` before every operation and writes through a per-process temporary file, so the gateway no longer keeps a stale copy of a forgotten key or writes it back when the device reconnects.
- The plugin checks, before the gateway creates the adapter, that this Hermes still has every module and private adapter hook it relies on, and fails with a message naming what is missing and the Hermes commit it was tested against. The tested commit lives in `plugin/compat.py`, and a test keeps it equal to the CI pin.

### Tools and documentation

- The changelog check skips Dependabot's pull requests and reads the `no-changelog` label live, so adding the label and re-running the job is enough.
- CI pins every action to a commit and PlatformIO to a version, gives every job a timeout, and checks that each pull request adds a changelog line (or carries the `no-changelog` label). A release now requires successful CI and Hermes runs on the tagged commit and a `CHANGELOG.md` section for the version, which opens the release notes. The security policy covers `linux/`.
- The Linux guide names the real approval command, `hermes gadget pair`. A test now checks that every `hermes gadget` and `hermes-gadget` command in the guides exists in the CLIs.

## 0.2.0

### Hardware and setup

- Add firmware profiles for Waveshare AMOLED-1.75C, Espressif ESP32-S3-BOX-3, M5Stack CoreS3, and LilyGO T-Display-S3. The release now covers seven ESP32-S3 profiles.
- Add phone-based Wi-Fi setup and recovery, including the dual-stack socket fix that allows IPv4 setup clients.
- Add on-device settings, hardware checks, and board-specific battery, brightness, and power controls.
- Provide Linux ARM64 packages for the experimental Raspberry Pi 4/5 client, including a service installer, updates, optional USB audio, GPIO controls, and display support.

### Tools and documentation

- Add a face generator and interactive feature picker. Copied commands preserve the image path, crop, rendering settings, and selected feature positions.
- Update the website, guided browser installer, and simulator conversation view. Publish setup, troubleshooting, hardware, and development guides with search.
- Add Home Assistant and MQTT examples and a Tailscale Funnel remote-access guide.
- Bundle third-party licenses and notices with firmware and Linux packages, with a license archive link in the installer.
- Add contribution requirements and automated documentation checks. CI discovers firmware profiles directly from PlatformIO.

### Upgrade and verification

Use the [browser installer](https://adolanium.github.io/hermes-gadget-sdk/installer.html) for USB installation. Existing devices can use `hermes gadget update <device> --latest`. Install the matching plugin using the commit-pinned command in the release notes. See [Linux installation and updates](docs/linux.md) for Raspberry Pi packages.

Hardware ports remain experimental until a complete physical report is recorded for the exact revision. The T-Display-S3 has a contributor-reported smoke check, but no complete verification report. Software tests and successful builds do not establish physical hardware verification; see [hardware capabilities and verification](docs/hardware-validation.md).

### Contributors

Thanks to [Angel Moreno (@Angel-M-R)](https://github.com/Angel-M-R) for the phone setup fix, [@rushter777](https://github.com/rushter777) for the face generator and picker, [Richard Ahlquist (@rahlquist)](https://github.com/rahlquist) for the T-Display-S3 port, and [Don F. Irwin (@0xdfi)](https://github.com/0xdfi) for the Funnel guide.

Earlier release notes: [0.1.1](https://github.com/Adolanium/hermes-gadget-sdk/releases/tag/v0.1.1) and [0.1.0](https://github.com/Adolanium/hermes-gadget-sdk/releases/tag/v0.1.0).
