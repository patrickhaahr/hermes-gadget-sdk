"""Live calls over the gadget connection: admission, ownership, stale answers and compatibility.

Devices are raw protocol clients (the Android client sends call.* itself, not the
device core) against the real hub. The voice service is a scripted broker: it
stands in for the Hermes Live Voice broker at the hub's CallBroker seam and
knows nothing about devices or ownership, so every isolation property below is
the hub's own. Nothing here touches the subscription service.
"""

from __future__ import annotations

import base64
import itertools
import json
import queue
import secrets
import threading
import time

import pytest

from conftest import requires_sim
from hermes_gadget_plugin import calls as callsmod
from hermes_gadget_plugin import protocol
from hermes_gadget_plugin.calls import CallError


class ScriptedBroker:
    """The voice service: an offer containing "slow" answers late, "fail" is refused."""

    def __init__(self, slow_s: float = 0.6):
        self.slow_s = slow_s
        self.started: list[dict] = []
        self.stopped: list[str] = []
        self.live: set[str] = set()
        self._ids = itertools.count(1)
        self._lock = threading.Lock()

    def start(self, *, profile, offer, language):
        with self._lock:
            ref = f"thread-{next(self._ids)}"
            self.started.append({"ref": ref, "profile": profile, "offer": offer, "language": language})
        if "slow" in offer:
            time.sleep(self.slow_s)
        if "fail" in offer:
            raise CallError("live_start_failed", "upstream refused the session")
        with self._lock:
            self.live.add(ref)
        return ref, "answer:" + offer

    def stop(self, ref):
        with self._lock:
            self.stopped.append(ref)
            was_live = ref in self.live
            self.live.discard(ref)
        return was_live

    def ref_for(self, offer: str) -> str:
        return next(s["ref"] for s in self.started if s["offer"] == offer)

    def wait_stopped(self, ref: str, timeout: float = 3.0) -> None:
        end = time.monotonic() + timeout
        while ref not in self.stopped:
            assert time.monotonic() < end, f"{ref} was never hung up; stopped: {self.stopped}"
            time.sleep(0.02)


class Device:
    """A paired-device protocol client that answers pings and keeps every frame it receives."""

    def __init__(self, url: str, name: str = "phone"):
        from websockets.sync.client import connect

        self.key = secrets.token_bytes(32)
        self.device_id = protocol.device_id_for_key(self.key)
        self.ws = connect(url, subprotocols=[protocol.SUBPROTOCOL], open_timeout=5)
        self.ws.send(json.dumps(protocol.message("hello", proto=protocol.VERSION, device_id=self.device_id,
                                                 name=name, board="android", caps={})))
        assert json.loads(self.ws.recv(timeout=5))["type"] == "challenge"
        self.ws.send(json.dumps(protocol.message("auth", key=base64.b64encode(self.key).decode())))
        self.welcome = json.loads(self.ws.recv(timeout=5))
        assert self.welcome["type"] == "welcome", self.welcome
        self.frames: queue.Queue = queue.Queue()
        self.seen: list[dict] = []
        self.closed = threading.Event()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        try:
            for raw in self.ws:
                if isinstance(raw, bytes):
                    continue
                msg = json.loads(raw)
                if msg.get("type") == "ping":
                    self.ws.send(json.dumps(protocol.message("pong", ts=msg.get("ts"))))
                self.seen.append(msg)
                self.frames.put(msg)
        except Exception:
            pass
        self.closed.set()

    def send(self, type_: str, **fields) -> None:
        self.ws.send(json.dumps(protocol.message(type_, **fields)))

    def expect(self, type_: str, timeout: float = 5.0, **match) -> dict:
        end = time.monotonic() + timeout
        while True:
            left = end - time.monotonic()
            assert left > 0, f"no {type_} {match}; received: {self.seen}"
            try:
                msg = self.frames.get(timeout=left)
            except queue.Empty:
                continue
            if msg.get("type") == type_ and all(msg.get(k) == v for k, v in match.items()):
                return msg

    def received(self, type_: str) -> list[dict]:
        return [m for m in self.seen if m.get("type") == type_]

    def close(self):
        self.ws.close()


@pytest.fixture
def calls_hub(devserver):
    """A hub with live calls on, its development brain and the scripted service."""

    def make(require_pairing: bool = True, broker: ScriptedBroker | None = None):
        broker = broker or ScriptedBroker()
        import hermes_gadget_plugin.hub as hubmod

        original = hubmod.DeviceHub.__init__

        def with_broker(self, *args, **kw):
            original(self, *args, call_broker=broker, **kw)

        hubmod.DeviceHub.__init__ = with_broker
        try:
            hub, brain, url = devserver(require_pairing=require_pairing)
        finally:
            hubmod.DeviceHub.__init__ = original
        return hub, brain, url, broker

    devices: list[Device] = []

    def device(url: str, brain=None, name: str = "phone") -> Device:
        dev = Device(url, name)
        devices.append(dev)
        if brain is not None:
            brain.approved.add(dev.device_id)
        return dev

    make.device = device
    yield make
    for dev in devices:
        dev.close()


def test_a_paired_device_starts_and_ends_its_own_call(calls_hub):
    hub, brain, url, broker = calls_hub(require_pairing=False)
    phone = calls_hub.device(url)
    assert phone.welcome["calls"] == ["live"]

    phone.send("call.start", id="c1", offer="offer-phone", language="en",
               profile="someone-else", device_id="hg-0000000000000000")
    answer = phone.expect("call.answer", id="c1")
    assert answer["answer"] == "answer:offer-phone"
    [started] = broker.started
    assert started["profile"] is None, "the device's own profile field was trusted"
    assert started["language"] == "en"

    phone.send("call.stop", id="c1")
    assert phone.expect("call.ended", id="c1")["reason"] == "hangup"
    assert broker.stopped == [started["ref"]]

    # Hang-up ends the call, not the gadget connection.
    phone.send("ping", ts=7)
    assert phone.expect("pong")["ts"] == 7
    assert hub.get(phone.device_id) is not None


def test_only_a_currently_paired_device_may_call(calls_hub, loop_thread):
    hub, brain, url, broker = calls_hub(require_pairing=True)
    stranger = calls_hub.device(url)  # enrolled, never approved
    stranger.send("call.start", id="c1", offer="offer-stranger")
    assert stranger.expect("call.error", id="c1")["code"] == "not_paired"
    assert broker.started == []

    phone = calls_hub.device(url, brain)
    loop_thread.run(hub.get(phone.device_id).set_paired(True))  # as approval does
    phone.expect("paired")
    phone.send("call.start", id="c2", offer="offer-phone")
    phone.expect("call.answer", id="c2")
    ref = broker.ref_for("offer-phone")

    # Revoking the pairing ends the call it admitted.
    brain.approved.discard(phone.device_id)
    loop_thread.run(hub.get(phone.device_id).set_paired(False))
    phone.expect("unpaired")
    assert phone.expect("call.ended", id="c2")["reason"] == "unpaired"
    broker.wait_stopped(ref)
    phone.send("call.start", id="c3", offer="offer-phone-again")
    assert phone.expect("call.error", id="c3")["code"] == "not_paired"
    assert len(broker.started) == 1


def test_a_call_answered_after_the_device_hung_up_is_hung_up_at_once(calls_hub):
    hub, brain, url, broker = calls_hub(require_pairing=False)
    phone = calls_hub.device(url)
    phone.send("call.start", id="c1", offer="offer-phone slow")
    time.sleep(0.1)
    phone.send("call.stop", id="c1")
    assert phone.expect("call.ended", id="c1")["reason"] == "hangup"

    broker.wait_stopped(broker.ref_for("offer-phone slow"))
    time.sleep(0.2)
    assert phone.received("call.answer") == [], "a late answer reached the device"

    # The device can call again afterwards.
    phone.send("call.start", id="c2", offer="offer-phone-2")
    assert phone.expect("call.answer", id="c2")["answer"] == "answer:offer-phone-2"


def test_a_late_answer_after_the_startup_deadline_is_hung_up(calls_hub, monkeypatch):
    monkeypatch.setattr(callsmod, "START_TIMEOUT_S", 0.2)
    hub, brain, url, broker = calls_hub(require_pairing=False, broker=ScriptedBroker(slow_s=0.6))
    phone = calls_hub.device(url)
    phone.send("call.start", id="c1", offer="offer-phone slow")
    assert phone.expect("call.error", id="c1")["code"] == "live_timeout"
    broker.wait_stopped(broker.ref_for("offer-phone slow"))
    assert phone.received("call.answer") == []


def test_failed_startup_reports_and_leaves_nothing_behind(calls_hub):
    hub, brain, url, broker = calls_hub(require_pairing=False)
    phone = calls_hub.device(url)
    phone.send("call.start", id="c1", offer="offer-phone fail")
    error = phone.expect("call.error", id="c1")
    assert error["code"] == "live_start_failed" and "refused" in error["message"]
    phone.send("call.start", id="c2", offer="offer-phone")
    phone.expect("call.answer", id="c2")
    assert len(broker.started) == 2, "a failed start was retried"


def test_one_call_per_device_and_stops_must_name_it(calls_hub):
    hub, brain, url, broker = calls_hub(require_pairing=False)
    phone = calls_hub.device(url)
    phone.send("call.start", id="c1", offer="offer-phone")
    phone.expect("call.answer", id="c1")

    phone.send("call.start", id="c2", offer="offer-phone-second")
    assert phone.expect("call.error", id="c2")["code"] == "busy"
    phone.send("call.stop", id="c-old")
    assert phone.expect("call.ended", id="c-old")["reason"] == "not_found"
    assert broker.stopped == [] and len(broker.started) == 1

    phone.send("call.start", id="c3")  # no offer
    assert phone.expect("call.error", id="c3")["code"] == "bad_request"


def test_calls_of_different_devices_are_independent(calls_hub):
    hub, brain, url, broker = calls_hub(require_pairing=False)
    phone = calls_hub.device(url, name="phone")
    other = calls_hub.device(url, name="other")
    phone.send("call.start", id="c1", offer="offer-phone slow")
    other.send("call.start", id="c1", offer="offer-other")
    assert other.expect("call.answer", id="c1")["answer"] == "answer:offer-other"
    assert phone.expect("call.answer", id="c1")["answer"] == "answer:offer-phone slow"

    other.send("call.stop", id="c1")
    other.expect("call.ended", id="c1")
    assert broker.stopped == [broker.ref_for("offer-other")]

    # The other device going away ends only its own call.
    third = calls_hub.device(url, name="third")
    third.send("call.start", id="c9", offer="offer-third")
    third.expect("call.answer", id="c9")
    third.close()
    broker.wait_stopped(broker.ref_for("offer-third"))
    assert broker.ref_for("offer-phone slow") not in broker.stopped
    assert [m["id"] for m in phone.received("call.answer")] == ["c1"]
    assert phone.received("call.ended") == []


def test_a_host_without_live_calls_says_so(devserver):
    hub, brain, url = devserver(require_pairing=False)
    phone = Device(url)
    try:
        assert "calls" not in phone.welcome
        phone.send("call.start", id="c1", offer="offer-phone")
        assert phone.expect("call.error", id="c1")["code"] == "unsupported"
    finally:
        phone.close()


@requires_sim
def test_existing_devices_work_with_a_calling_host(calls_hub, make_sim):
    """The device core ignores welcome.calls; a device that never calls is unaffected."""
    hub, brain, url, broker = calls_hub(require_pairing=False)
    sim = make_sim(url)
    assert sim.wait_screen("ready", timeout=10)
    sim.type_text("hello")
    assert sim.wait_for(lambda: (sim.last_received("reply") or {}).get("text"), timeout=10)
    assert broker.started == []
