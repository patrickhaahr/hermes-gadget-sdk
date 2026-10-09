# Activate one Hermes voice request without a ready cue

Issue #10 amends [ADR 0001](0001-local-wake-before-voice-calls.md) for Hermes voice mode. A saved Voice mode chooses Hermes voice (default) or subscription Live voice (selection was deferred until the Live call path was ready).

In Hermes voice mode, the user says "Hey Hermes" followed by a request in one breath or after a short pause. There is no ready cue: playing a cue into the active microphone would contaminate the request. The running capture transfers to the core without reopening the microphone. Ordered audio starts with the 80 ms detection chunk. Earlier detector chunks are discarded, with no pre-roll buffer or upload. The tail of the wake phrase can reach STT.

The core buffers only post-detection request audio in memory, up to 30 seconds. Local end-of-speech detection submits it through the existing audio.start/binary/audio.end protocol and Hermes's configured STT/TTS. No speech, swipe cancellation or Microphone off discards the buffer without uploading. This buffering also keeps cancellation private without changing the server protocol. Existing hold-to-talk remains streamed as before.

Wake activation is a distinct guarded core entry point, never a synthetic TALK press. Connection, pairing, pending prompts, active turns and setup block it. It cannot approve anything, interrupt an existing turn, or open an automatic follow-up. Playback pauses wake listening, including the existing 500 ms tail.

Live voice retains ADR 0001's activation, usable call, ready cue, then request sequence. This decision adds no WebRTC, broker, Codex, call isolation or new voice backend.

Issue #8 completes that deferred Live selection and wake-to-call path. The mode remains an explicit saved choice: Hermes voice records one request without a cue; Live voice discards wake capture, starts the call, then plays its ready cue. Failure never selects another mode.
