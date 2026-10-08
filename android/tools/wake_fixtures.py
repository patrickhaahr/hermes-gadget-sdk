"""Regenerates the wake-word test fixtures and their recorded expected behaviour.

The fixtures in android/app/src/wakeFixtures are synthetic speech from Piper
voices, resampled to 16 kHz mono PCM16. expected.txt records what the
reference engine (pyopen-wakeword, which Hermes Agent's desktop wake word
uses) scores for each 80 ms chunk, with the app's detection rule: a score of
at least 0.8 in one chunk, then a reset. The Android detector is
tested against it.

    uv run --with piper-tts==1.8.0 --with pyopen-wakeword==1.1.0 \\
        --with scipy==1.18.1 --with numpy==2.5.3 \\
        python android/tools/wake_fixtures.py [--speech-only|--expected-only]

Piper's synthesis is not deterministic, so new audio needs a new expected.txt;
--expected-only rescores the committed audio.
"""

from __future__ import annotations

import argparse
import hashlib
import urllib.request
import wave
from math import gcd
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
FIXTURES = HERE.parent / "app" / "src" / "wakeFixtures"
CACHE = HERE.parent / "build" / "piper-voices"

RATE = 16000
CHUNK = 1280
THRESHOLD = 0.8  # WakeDetector.DEFAULT_THRESHOLD
CONFIRMATIONS = 1  # WakeDetector.DEFAULT_CONFIRMATIONS

# rhasspy/piper-voices at a fixed revision.
VOICES_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/c10ece1aade47bb51c153c893d14e5bf8e5b7117/en/en_US"
VOICES = {
    # LJ Speech: public domain.
    "en_US-ljspeech-high": ("ljspeech/high", "5d4f08ba6a2a48c44592eed3ce56bf85e9de3dd4e20df90541ae68a8310c029a"),
    # LibriTTS-R: CC BY 4.0.
    "en_US-libritts_r-medium": ("libritts_r/medium", "10bb85e071d616fcf4071f369f1799d0491492ab3c5d552ec19fb548fac13195"),
}

# file -> list of (voice, speaker, text); each utterance is followed by silence.
SPEECH = {
    "hey_hermes_ljspeech.wav": [("en_US-ljspeech-high", None, "Hey Hermes.")],
    "hey_hermes_libritts.wav": [("en_US-libritts_r-medium", 300, "Hey Hermes.")],
    "unrelated_speech.wav": [
        ("en_US-ljspeech-high", None, "The kettle is boiling, could you pour two cups of tea for us?"),
        ("en_US-libritts_r-medium", 100, "I think we should leave before the traffic gets worse."),
    ],
    "near_miss.wav": [
        ("en_US-ljspeech-high", None, "Hey, her mess is on the table."),
        ("en_US-ljspeech-high", None, "Hermes is a Greek god."),
    ],
}

LEAD_S, GAP_S = 0.5, 1.0


def voice(name: str) -> Path:
    path, sha = VOICES[name]
    CACHE.mkdir(parents=True, exist_ok=True)
    model = CACHE / f"{name}.onnx"
    for target, suffix in ((model, ".onnx"), (CACHE / f"{name}.onnx.json", ".onnx.json")):
        if not target.exists():
            urllib.request.urlretrieve(f"{VOICES_URL}/{path}/{name}{suffix}", target)  # noqa: S310
    if hashlib.sha256(model.read_bytes()).hexdigest() != sha:
        raise SystemExit(f"{model} does not match the pinned voice")
    return model


def to_16k(pcm: np.ndarray, rate: int) -> np.ndarray:
    from scipy.signal import resample_poly

    g = gcd(RATE, rate)
    out = resample_poly(pcm.astype(np.float64), RATE // g, rate // g)
    return np.clip(np.round(out), -32768, 32767).astype(np.int16)


def synthesize() -> None:
    import io

    from piper import PiperVoice, SynthesisConfig

    loaded: dict[str, PiperVoice] = {}
    for filename, parts in SPEECH.items():
        audio = [np.zeros(int(LEAD_S * RATE), np.int16)]
        for name, speaker, text in parts:
            v = loaded.setdefault(name, PiperVoice.load(voice(name)))
            buf = io.BytesIO()
            with wave.open(buf, "wb") as w:
                v.synthesize_wav(text, w, SynthesisConfig(speaker_id=speaker))
            buf.seek(0)
            with wave.open(buf) as w:
                pcm = np.frombuffer(w.readframes(w.getnframes()), np.int16)
                audio += [to_16k(pcm, w.getframerate()), np.zeros(int(GAP_S * RATE), np.int16)]
        pcm = np.concatenate(audio)
        pcm = np.pad(pcm, (0, (-len(pcm)) % CHUNK))
        with wave.open(str(FIXTURES / filename), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(pcm.tobytes())


def read(path: Path) -> np.ndarray:
    with wave.open(str(path)) as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, RATE), path
        return np.frombuffer(w.readframes(w.getnframes()), np.int16)


def score() -> None:
    from pyopen_wakeword import OpenWakeWord, OpenWakeWordFeatures

    hermes_model = HERE.parent / "app" / "build" / "wake-models" / "wake" / "hey_hermes.tflite"
    if not hermes_model.exists():
        raise SystemExit(f"{hermes_model} is missing: run ./gradlew downloadWakeModels first")
    features = OpenWakeWordFeatures.from_builtin()
    model = OpenWakeWord.from_model(hermes_model)
    results = {}
    for path in sorted(FIXTURES.glob("*.wav")):
        pcm = read(path)
        features.reset()
        model.reset()
        streak, scores, detections = 0, [], []
        for i in range(0, len(pcm), CHUNK):
            chunk = pcm[i : i + CHUNK]
            got = [s for emb in features.process_streaming(chunk.tobytes()) for s in model.process_streaming(emb)]
            top = max(got) if got else None
            scores.append(None if top is None else round(float(top), 5))
            streak = streak + 1 if top is not None and top >= THRESHOLD else 0
            if streak >= CONFIRMATIONS:
                detections.append(i // CHUNK)
                streak = 0
                features.reset()
                model.reset()
        results[path.name] = {"detections": detections, "scores": scores}
    lines = [
        "# Recorded by android/tools/wake_fixtures.py with pyopen-wakeword 1.1.0: per fixture, the",
        f"# 80 ms chunks where the phrase was confirmed (score >= {THRESHOLD} in {CONFIRMATIONS} consecutive chunks,",
        "# then a reset), and each chunk's highest score (- where a chunk completed no window).",
    ]
    for name, r in results.items():
        lines.append(f"{name} detections={','.join(map(str, r['detections']))}")
        lines.append(f"{name} scores=" + " ".join("-" if v is None else f"{v:.5f}" for v in r["scores"]))
    (FIXTURES / "expected.txt").write_text("\n".join(lines) + "\n")
    for name, r in results.items():
        print(f"{name}: detections at chunks {r['detections']}, peak {max(s or 0 for s in r['scores']):.3f}")


def main() -> None:
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--speech-only", action="store_true")
    group.add_argument("--expected-only", action="store_true")
    args = parser.parse_args()
    FIXTURES.mkdir(parents=True, exist_ok=True)
    if not args.expected_only:
        synthesize()
    if not args.speech_only:
        score()


if __name__ == "__main__":
    main()
