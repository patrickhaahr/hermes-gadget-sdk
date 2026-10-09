# Repository work

Read [CONTRIBUTING.md](CONTRIBUTING.md) before changing this repository. It defines the documentation, verification, licensing, and contributor-credit requirements for every PR.

## Find the relevant guidance

- For firmware or hardware work, read [development](docs/development.md), [porting](docs/porting.md), and [hardware verification](docs/hardware-validation.md). Use the project's configured ESP-IDF APIs and the board checklist in CONTRIBUTING.md.
- For CLI, simulator, plugin, or Linux behavior, read the corresponding user guide and existing tests before changing the interface. The contribution guide maps change types to documentation and verification.
- For website or guide changes, inspect `site/package.json` for commands and `site/scripts/docs.mjs` for published pages. A new guide needs working navigation, search, and links.
- For vendor-derived code or register data, follow the licensing section in CONTRIBUTING.md and inspect `NOTICE` and `THIRD_PARTY_NOTICES.md` before adding material.

## Complete the change

1. Inspect the working tree and preserve unrelated user changes. Keep the implementation scoped to the requested behavior.
2. Update the affected documentation in the same PR, or explain why no documentation changes apply.
3. Run the relevant checks from [development](docs/development.md). For a bug fix, verify the observable failure and corrected result where practical. Do not weaken assertions to make a failure disappear.
4. Review the final diff for unintended changes, missing notices, and stale instructions.
5. Report what changed, the checks run, their results, and any remaining limitations. Distinguish hardware measurements, simulations, test doubles, and untested assumptions.

Keep contributor authorship and credit when fixing or salvaging another person's work. Merge only when authorized and the current PR revision passes CI. Do not describe an experimental hardware port as verified without the required physical report.

## Android Live voice gotchas

- Read the OnePlus 8T echo and interruption report in `docs/hardware-validation.md` before changing the call audio path. On that phone, WebRTC uses the platform echo canceller and noise suppressor and disables its software equivalents. The ready cue plays outside WebRTC; its rejection depends on the platform echo canceller.
- Spoken interruption is handled by the voice service. The phone has no gateway interrupt route, and `/codexlive/interrupt` does not stop speech on codex-cli 0.160.0. Preserve independent phone and desktop calls when adding handoffs.
- The service sometimes loses words spoken over playback or at the start of a call. Seven measured interruptions stopped playback after 0.7–2.8 seconds, but two needed repetition. Do not infer reliable transcription from successful interruption.
- WebRTC's `media-source.audioLevel` stayed zero on the 8T. Use captured PCM levels for diagnostics, and keep audio and transcript content out of committed logging. Measurements from temporary instrumentation are separate from host tests with scripted media.
- Update the configured phone with `adb install -r` using its existing debug signing key. Do not uninstall it or run `connectedAndroidTest`; that loses gadget identity and device-owner setup. Wireless ADB ports change; the last tested endpoint was `10.0.10.156:39859`, separate from the gadget URL `ws://10.0.10.3:8765/gadget`.
- Live tasks travel from `WebRtcCallMedia`'s native `delegation.created` through `LiveCall` as `call.task`. `call.task.status` binds call id, delegation id and the actual Hermes message/turn id. Resolve the native item only on a terminal receipt, once; duplicate events and ambiguous transport failures must not resubmit work. Hosts advertise `welcome.call_tasks` separately from calls.
- The adapter owns accepted task tracking, not `DeviceCalls.end_device`. Hang-up, disconnect and Microphone off must leave accepted work running and retain its slot until `on_processing_complete`. Keep both streaming and file gadget TTS suppressed for that task, including after hang-up. Only the still-authorized originating call may receive its result; a new call gets busy feedback while old work remains active.
- Submit Live delegations as TEXT with `allow_gateway_control=False` to the existing device DM in the adapter's profile. VOICE would select `auxiliary.voice_chat`. Admission checks Hermes's `_event_session_key` / `_active_sessions` guard before dispatch and uses `_gateway_accepted`; these private contracts are listed in `plugin/compat.py` and need real-gateway regression coverage when Hermes changes.
- The Live Voice public `start_call` API now has the phone's conversation policy; dashboard routes keep their desktop queue/wait policy. Stories and small talk belong to the voice, and casual conversation remains available during tasks. Keep execution restrictions in the broker and approvals on-screen; prompt wording alone is not an execution restriction.
- SDK voice work targets fork branch `android/client`, not upstream `main`. The configured gateway uses a plain plugin copy in `~/.hermes/plugins/gadget`; deploy SDK Python changes there and restart `hermes-agent`. The Live Voice install is a separate checkout. Use the packaged Hermes Python and locales for host checks; `HERMES_GADGET_TEST_PYTHON` supports that runtime in gateway E2E, and `HERMES_LIVE_VOICE_DIR` must name the broker checkout with its fake app-server. See `docs/development.md` and the issue #6 hardware report for commands and measured limits.
