"""Full end-to-end: a real ``hermes gateway run`` with the gadget plugin
installed, a simulated device, and a fake OpenAI-compatible server standing in
for the model, speech-to-text and text-to-speech.

Opt-in (spawns processes, ~1 minute):
    HERMES_GADGET_E2E=1 pytest tests/test_gateway_e2e.py
Needs a Hermes Agent checkout with a working virtualenv (HERMES_AGENT_DIR,
default ../hermes-agent; the gateway runs with <dir>/.venv's Python).
"""

from __future__ import annotations

import math
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

import pytest

from conftest import REPO, requires_sim

AGENT_DIR = Path(os.environ.get("HERMES_AGENT_DIR", REPO.parent / "hermes-agent"))
VENV_PY = Path(os.environ.get("HERMES_GADGET_TEST_PYTHON", AGENT_DIR / ".venv" / (
    "Scripts/python.exe" if sys.platform == "win32" else "bin/python")))

pytestmark = [
    requires_sim,
    pytest.mark.skipif(os.environ.get("HERMES_GADGET_E2E") != "1", reason="set HERMES_GADGET_E2E=1 to run"),
    pytest.mark.skipif(not VENV_PY.exists(), reason=f"no Hermes virtualenv at {VENV_PY}"),
]

CONFIG = """\
model:
  provider: custom
  default: fake-gadget-model
  base_url: http://127.0.0.1:{api}/v1
  api_key: test-key
platforms:
  gadget:
    enabled: true
    extra:
      host: 127.0.0.1
      port: {gadget}
      unauthorized_dm_behavior: pair
plugins:
  enabled: [gadget]
streaming:
  enabled: true   # stream reply text to messaging platforms (the device) as it is generated
stt:
  enabled: true
  provider: openai
  openai: {{api_key: test-key, base_url: "http://127.0.0.1:{api}/v1", model: whisper-1}}
tts:
  provider: openai
  openai: {{api_key: test-key, base_url: "http://127.0.0.1:{api}/v1", model: tts-1, voice: alloy}}
"""


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _hermes(home: Path, *args: str, **kw) -> subprocess.CompletedProcess:
    env = {**os.environ, "HERMES_HOME": str(home), "PYTHONUTF8": "1"}
    return subprocess.run([str(VENV_PY), "-m", "hermes_cli.main", *args], cwd=AGENT_DIR, env=env,
                          capture_output=True, text=True, timeout=180, **kw)


@pytest.fixture
def gateway(tmp_path, request):
    from fakes import fake_openai

    api_server, api_port = fake_openai.start(0)
    gadget_port = _free_port()
    home = tmp_path / "hermes-home"
    (home / "plugins").mkdir(parents=True)
    shutil.copytree(REPO / "plugin", home / "plugins" / "gadget", ignore=shutil.ignore_patterns("__pycache__"))
    config = CONFIG.format(api=api_port, gadget=gadget_port)
    env = {**os.environ, "HERMES_HOME": str(home), "PYTHONUTF8": "1"}
    if getattr(request, "param", None) == "calls":
        source = Path(os.environ.get("HERMES_LIVE_VOICE_DIR", REPO.parent / "hermes-live-voice")).resolve()
        if not (source / "tests" / "fake_codex_app_server.py").exists():
            pytest.skip("needs HERMES_LIVE_VOICE_DIR with the device-call broker")
        shutil.copytree(source / "dashboard", home / "plugins" / "talk-desktop" / "dashboard",
                        ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copy(source / "language_directive.txt", home / "plugins" / "talk-desktop")
        codex = tmp_path / "codex"
        codex.write_text(f'#!/bin/sh\nexec "{VENV_PY}" "{source / "tests" / "fake_codex_app_server.py"}" "$@"\n')
        codex.chmod(0o755)
        env["TALK_CODEX_BINARY"] = str(codex)
        config = config.replace("      port:", "      live_calls: true\n      port:")
    (home / "config.yaml").write_text(config, encoding="utf-8")
    log = open(tmp_path / "gateway.out", "w", encoding="utf-8")
    proc = subprocess.Popen([str(VENV_PY), "-m", "hermes_cli.main", "gateway", "run"], cwd=AGENT_DIR, env=env,
                            stdout=log, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 120
    url = f"ws://127.0.0.1:{gadget_port}/gadget"
    while time.monotonic() < deadline:
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", gadget_port)) == 0:
                break
        if proc.poll() is not None:
            pytest.fail(f"gateway exited: {(tmp_path / 'gateway.out').read_text(errors='replace')[-3000:]}")
        time.sleep(0.5)
    else:
        pytest.fail("gateway did not open the gadget port")
    yield {"url": url, "home": home, "api": fake_openai.Handler.requests_log, "model": fake_openai.Handler}
    fake_openai.Handler.release.set()
    proc.terminate()
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        proc.kill()
    log.close()
    api_server.shutdown()


def test_device_pairs_talks_and_is_driven_by_the_agent(gateway, make_sim):
    sim = make_sim(gateway["url"], name="E2E Gadget")

    # Pairing goes through Hermes's own DM pairing codes.
    assert sim.wait_for(lambda: sim.status().get("pairing_code"), timeout=60)
    code = sim.status()["pairing_code"]
    approved = _hermes(gateway["home"], "pairing", "approve", "gadget", code)
    assert approved.returncode == 0, approved.stdout + approved.stderr
    assert sim.wait_screen("ready", timeout=30)

    # Text in, model reply out, streamed as it is generated.
    sim.received.clear()
    sim.type_text("hello hermes")
    assert sim.wait_for(lambda: "You said: hello hermes" in (sim.last_received("reply") or {}).get("text", ""),
                        timeout=90)
    assert sim.last_received("reply.delta") is not None, [m["type"] for m in sim.received]
    assert sim.wait_for(lambda: sim.last_received("turn.end") is not None, timeout=30)

    # Voice in: Hermes STT -> model -> reply spoken through Hermes TTS.
    sim.received.clear()
    pcm = b"".join(struct.pack("<h", int(7000 * math.sin(2 * math.pi * 300 * i / 16000))) for i in range(16000))
    sim.speak_pcm(pcm)
    assert sim.wait_for(lambda: sim.last_received("turn.end") is not None, timeout=90)
    assert sim.last_received("transcript")["text"] == "what is on my screen"
    assert "what is on my screen" in sim.last_received("reply")["text"]
    assert sim.last_received("audio.start") is not None

    # The agent drives the device through the gadget tools.
    sim.received.clear()
    sim.type_text("put a note on my screen")
    assert sim.wait_for(lambda: sim.last_received("display") is not None, timeout=90)
    assert sim.last_received("display")["body"] == "Hello from Hermes"
    sim.tap("cancel")
    sim.received.clear()
    sim.type_text("turn the led on")
    assert sim.wait_for(lambda: sim.peripherals.led == "green", timeout=90)
    assert sim.wait_for(lambda: sim.last_received("turn.end") is not None, timeout=60)

    # Holding CANCEL starts a new Hermes session; the hold was the confirmation, so the
    # gateway's "Confirm /new" is answered by the plugin and the reset reply arrives.
    sim.received.clear()
    sim.console("new-session")
    assert sim.wait_for(lambda: sim.last_received("reply") is not None, timeout=60), sim.received
    assert "Confirm" not in sim.last_received("reply")["text"]
    assert sim.last_received("prompt") is None

    # A typed /new asks on the device instead, and TALK answers yes.
    sim.run_for(1.0)
    sim.received.clear()
    sim.type_text("/new")
    assert sim.wait_screen("prompt", timeout=60), sim.received
    assert "/new" in sim.last_received("prompt")["title"]
    sim.run_for(0.7)
    sim.tap("talk")
    assert sim.wait_for(lambda: sim.last_received("reply") is not None, timeout=60), sim.received
    assert sim.device.screen() != "prompt"
    paths = [r["path"] for r in gateway["api"]]
    assert any(p.endswith("/audio/transcriptions") for p in paths)
    assert any(p.endswith("/audio/speech") for p in paths)


@pytest.mark.parametrize("gateway", ["calls"], indirect=True)
def test_live_task_uses_the_gadget_history_and_returns_once_without_gadget_speech(gateway):
    from test_hub_calls import Device

    phone = Device(gateway["url"], caps={"speaker": {"rate": 24000}})
    model = gateway["model"]
    try:
        pairing = phone.expect("pairing", timeout=60)
        assert _hermes(gateway["home"], "pairing", "approve", "gadget", pairing["code"]).returncode == 0
        phone.expect("paired", timeout=30)
        phone.send("text", id="history", text="remember gadget continuity token")
        phone.expect("turn.end", timeout=90)
        audio_before = len(phone.received("audio.start"))
        tts_before = sum(r["path"].endswith("/audio/speech") for r in gateway["api"])
        phone.send("call.start", id="c1", offer="offer-phone", profile="someone-else")
        phone.expect("call.answer", id="c1", timeout=15)
        model.blocked_text = "put a note on my screen"
        model.entered.clear()
        model.release.clear()
        task = dict(id="c1", delegation="d1", text=model.blocked_text, device_id="another-device", profile="elsewhere")
        phone.send("call.task", **task)
        accepted = phone.expect("call.task.status", id="c1", delegation="d1", state="accepted")
        assert accepted["turn"]
        assert model.entered.wait(60), gateway["api"]
        phone.send("call.task", **task)
        assert phone.expect("call.task.status", delegation="d1", state="accepted")["turn"] == accepted["turn"]
        phone.send("call.task", id="c1", delegation="d2", text="turn the led on")
        assert "wait" in phone.expect("call.task.status", delegation="d2", state="busy")["text"].lower()
        model.release.set()
        phone.expect("display", timeout=90)  # the real gateway executed the gadget tool
        result = phone.expect("call.task.status", delegation="d1", state="completed", timeout=90)
        assert result["turn"] == accepted["turn"]
        assert "Done" in result["text"]
        phone.expect("turn.end", turn=accepted["turn"])
        assert len(phone.received("audio.start")) == audio_before, phone.seen
        assert sum(r["path"].endswith("/audio/speech") for r in gateway["api"]) == tts_before
        posts = [r for r in gateway["api"] if model.blocked_text in r.get("user", "")]
        assert len(posts) == 2  # one model request and one tool-result request, no duplicate task
        assert any("remember gadget continuity token" in str(r["messages"]) for r in posts)
        phone.send("call.task", **task)
        assert phone.expect("call.task.status", delegation="d1", state="completed")["turn"] == accepted["turn"]
        phone.send("call.stop", id="c1")
        phone.expect("call.ended", id="c1")
        phone.send("call.task", **task)
        phone.expect("call.task.status", delegation="d1", state="not_connected")

    finally:
        model.blocked_text = None
        model.release.set()
        phone.close()


@pytest.mark.parametrize("gateway", ["calls"], indirect=True)
@pytest.mark.parametrize("teardown", ["hangup", "disconnect", "replace_transport"])
def test_accepted_live_task_outlives_its_call_and_transport(gateway, teardown):
    import sqlite3

    from test_hub_calls import Device

    caps = {"speaker": {"rate": 24000}}
    phone = Device(gateway["url"], caps=caps)
    devices = [phone]
    model = gateway["model"]
    try:
        pairing = phone.expect("pairing", timeout=60)
        assert _hermes(gateway["home"], "pairing", "approve", "gadget", pairing["code"]).returncode == 0
        phone.expect("paired", timeout=30)
        phone.send("text", id="history", text="remember lifecycle continuity token")
        phone.expect("turn.end", timeout=90)
        tts_before = sum(r["path"].endswith("/audio/speech") for r in gateway["api"])
        phone.seen.clear()
        phone.send("call.start", id="old-call", offer="offer-old")
        phone.expect("call.answer", id="old-call")
        model.blocked_text = "check task after teardown"
        model.entered.clear()
        model.release.clear()
        phone.send("call.task", id="old-call", delegation="late", text=model.blocked_text)
        late = phone.expect("call.task.status", delegation="late", state="accepted")
        assert model.entered.wait(60)
        if teardown == "hangup":
            phone.send("call.stop", id="old-call")  # also the phone's Microphone off wire action
            phone.expect("call.ended", id="old-call")
        else:
            old = phone
            if teardown == "disconnect":
                old.close()
            phone = Device(gateway["url"], caps=caps, key=old.key)
            devices.append(phone)
            assert phone.welcome["paired"] is True
            assert old.closed.wait(5)

        phone.send("call.start", id="new-call", offer="offer-new")
        phone.expect("call.answer", id="new-call")
        # An old request cannot execute on a recreated transport. A fresh native
        # delegation may use the same item id, but it still waits for the old task.
        phone.send("call.task", id="old-call", delegation="late", text=model.blocked_text)
        phone.expect("call.task.status", id="old-call", delegation="late", state="not_connected")
        phone.send("call.task", id="new-call", delegation="late", text="never queued request")
        assert "wait" in phone.expect("call.task.status", id="new-call", delegation="late", state="busy")["text"].lower()
        model.release.set()
        assert phone.expect("turn.end", turn=late["turn"], timeout=90)["outcome"] == "success"
        assert not [m for d in devices for m in d.received("call.task.status") if m["state"] == "completed"]
        assert not any(d.received("audio.start") for d in devices)
        assert sum(r["path"].endswith("/audio/speech") for r in gateway["api"]) == tts_before
        posts = [r for r in gateway["api"] if model.blocked_text in r.get("user", "")]
        assert len(posts) == 1  # accepted work was never retried
        assert "remember lifecycle continuity token" in str(posts[0]["messages"])

        with sqlite3.connect(gateway["home"] / "state.db") as db:
            sessions = db.execute("SELECT id FROM sessions WHERE source = 'gadget' AND chat_id = ?",
                                  (phone.device_id,)).fetchall()
            assert len(sessions) == 1  # a new voice call did not reset Hermes history
            history = db.execute("SELECT role, content FROM messages WHERE session_id = ?", sessions[0]).fetchall()
        assert sum(role == "user" and model.blocked_text in (text or "") for role, text in history) == 1
        assert any(role == "assistant" and model.blocked_text in (text or "") for role, text in history)
        assert not any("never queued request" in (text or "") for _, text in history)

        # The terminal hook releases the slot; the still-open new call can now
        # submit a fresh task. Repeating its earlier busy item must not queue it.
        phone.send("call.task", id="new-call", delegation="late", text="never queued request")
        phone.expect("call.task.status", id="new-call", delegation="late", state="busy")
        phone.send("call.task", id="new-call", delegation="fresh", text="check fresh task")
        fresh = phone.expect("call.task.status", id="new-call", delegation="fresh", state="accepted")
        result = phone.expect("call.task.status", id="new-call", delegation="fresh", state="completed", timeout=90)
        assert result["turn"] == fresh["turn"] != late["turn"]
        phone.expect("turn.end", turn=fresh["turn"])
        assert not phone.received("audio.start")
    finally:
        model.blocked_text = None
        model.release.set()
        for device in devices:
            device.close()
