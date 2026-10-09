#!/usr/bin/env python3
"""Run the opt-in acoustic Live check: synthetic speech from the phone's own loudspeaker.

Generates the hold-to-talk request with the pinned Piper voice used for the wake
fixtures, pushes it to the phone and runs LiveAcousticDeviceTest against the
paired, configured Hermes host, with Live voice selected. Calls spend subscription
allowance. For the
failure scenario, stop the gateway's codex app-server first (kill -STOP) and
resume it afterwards.

    uv run --no-project --with piper-tts==1.8.0 --with scipy==1.18.1 --with numpy==2.5.3 \\
        python android/tools/live_acoustic.py [--scenario conversation|failure|hold_to_talk]
        [--cycles 3] [--screen-off]

adb must be on PATH (devenv shell) with a single device selected (ANDROID_SERIAL).
The test logs timings and counts, never transcripts or audio.
"""

from __future__ import annotations

import argparse
import io
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from live_task_lifecycle import relaunch  # noqa: E402
from wake_fixtures import CACHE, RATE, to_16k, voice  # noqa: E402

DEVICE_DIR = "/data/local/tmp/hg-live"
SPEECH = {"request.wav": "What is two plus two? Answer in one short sentence."}


def synthesize(out: Path) -> None:
    from piper import PiperVoice

    out.mkdir(parents=True, exist_ok=True)
    piper = PiperVoice.load(voice("en_US-ljspeech-high"))
    for name, text in SPEECH.items():
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            piper.synthesize_wav(text, w)
        buf.seek(0)
        with wave.open(buf) as w:
            pcm = to_16k(np.frombuffer(w.readframes(w.getnframes()), np.int16), w.getframerate())
        with wave.open(str(out / name), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(pcm.tobytes())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenario", default="conversation", choices=["conversation", "failure", "hold_to_talk"])
    parser.add_argument("--cycles", type=int, default=3)
    parser.add_argument("--screen-off", action="store_true")
    args = parser.parse_args()
    out = CACHE.parent / "live-acoustic"
    synthesize(out)
    subprocess.run(["adb", "shell", "mkdir", "-p", DEVICE_DIR], check=True)
    for name in SPEECH:
        subprocess.run(["adb", "push", "-q", str(out / name), f"{DEVICE_DIR}/{name}"], check=True)
    try:
        result = subprocess.run([
            "adb", "shell", "am", "instrument", "-w", "-e", "liveAcoustic", "true",
            "-e", "scenario", args.scenario, "-e", "cycles", str(args.cycles),
            "-e", "screenOff", str(args.screen_off).lower(), "-e", "speech", DEVICE_DIR,
            "-e", "class", "io.github.adolanium.hermesgadget.LiveAcousticDeviceTest",
            "io.github.adolanium.hermesgadget.test/androidx.test.runner.AndroidJUnitRunner",
        ], capture_output=True, text=True, timeout=1800)
    finally:
        relaunch()
    print(result.stdout)
    if result.stderr:
        print(result.stderr)
    subprocess.run(["adb", "logcat", "-d", "-s", "HermesAcousticTest"], check=False)
    return 0 if result.returncode == 0 and "OK (1 test)" in result.stdout else 1


if __name__ == "__main__":
    raise SystemExit(main())
