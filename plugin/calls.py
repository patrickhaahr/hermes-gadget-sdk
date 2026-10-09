"""Live calls for paired devices: a subscription GPT-Live voice call per device.

A device asks for a call over its gadget connection (``call.start`` with a WebRTC
SDP offer). The broker returns the SDP answer, and the call's audio then flows
between the device and the voice service directly; only signaling crosses this
connection. See "Live calls" in docs/protocol.md.

The broker is the Hermes Live Voice plugin's (``talk-desktop``). Its dashboard
routes run in the dashboard process and need a dashboard login, so this process
loads the plugin's module and runs a broker of its own, with its own
``codex app-server``. Nothing crosses to the dashboard (docs/adr/0006).

Who may call is decided here, on every request: the device must be currently
paired (not just enrolled), and the call runs in the adapter's own Hermes
profile. Fields a device sends about its identity or profile are ignored.
"""

from __future__ import annotations

import asyncio
import importlib.util
import logging
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Awaitable, Callable, Optional, Protocol

from . import protocol

log = logging.getLogger("hermes_gadget.calls")

KIND = "live"                # the call kind a server advertises in welcome.calls
START_TIMEOUT_S = 25.0       # backstop; the broker gives up at 20 s and the device too
MAX_OFFER_BYTES = 64 * 1024  # a WebRTC audio offer is a few KB
MAX_ID = 64
LANGUAGES = ("en", "da")     # the voices the broker speaks; English unless the device asks for Danish


class CallError(Exception):
    """A call could not start or be controlled; ``code`` is sent to the device."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class CallBroker(Protocol):
    def start(self, *, profile: Optional[str], offer: str, language: str) -> tuple[str, str]:
        """Start one isolated call (blocking); returns (call reference, SDP answer). Raises CallError."""

    def stop(self, ref: str) -> bool:
        """Hang up the call ``ref`` names (blocking); False if it is not live."""


class LiveVoiceBroker:
    """The Hermes Live Voice plugin's broker, running in this process."""

    def __init__(self, module):
        self._module = module

    def start(self, *, profile: Optional[str], offer: str, language: str) -> tuple[str, str]:
        try:
            result = self._module.start_call(profile=profile, offer=offer, language=language)
        except self._module.LiveCallError as exc:
            code, _, message = str(exc).partition(": ")
            raise CallError(exc.code.lower(), message or code) from exc
        except Exception as exc:  # the broker's own bugs must not take the hub down
            log.exception("live call broker failed")
            raise CallError("live_start_failed", str(exc)[:300]) from exc
        return str(result["threadId"]), str(result["answer"])

    def stop(self, ref: str) -> bool:
        return bool(self._module.stop_call(ref))


_loaded: dict[Path, LiveVoiceBroker] = {}


def load_live_voice(plugin_dir: Path) -> LiveVoiceBroker:
    """The Live Voice plugin's broker from its install directory, loaded once per process. Raises CallError.

    Once per process because the module owns its app-server: loading it again when the
    adapter reconnects would leave the previous one running.
    """
    source = (Path(plugin_dir).expanduser() / "dashboard" / "plugin_api.py").resolve()
    if source in _loaded:
        return _loaded[source]
    if not source.is_file():
        raise CallError("unsupported", f"the Hermes Live Voice plugin is not installed ({source} is missing)")
    name = "hermes_gadget_live_voice_broker" + (f"_{len(_loaded)}" if _loaded else "")
    spec = importlib.util.spec_from_file_location(name, source)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    try:
        spec.loader.exec_module(module)
    except Exception as exc:
        sys.modules.pop(name, None)
        raise CallError("unsupported", f"the Hermes Live Voice plugin did not load: {exc}") from exc
    if not callable(getattr(module, "start_call", None)) or not callable(getattr(module, "stop_call", None)):
        sys.modules.pop(name, None)
        raise CallError("unsupported", "this Hermes Live Voice plugin is too old for device calls "
                                       "(it has no start_call); update it")
    broker = _loaded[source] = LiveVoiceBroker(module)
    return broker


@dataclass(eq=False)
class _Call:
    id: str
    session: object  # hub.DeviceSession
    ref: Optional[str] = None  # the broker's reference, once started
    ended: bool = False
    profile: Optional[str] = None
    delegations: dict[str, dict] = field(default_factory=dict)  # receipts for this call; never re-execute an id


class DeviceCalls:
    """Each device's call: who may start one, which call a stop names, and cleanup.

    One call per device at a time. Calls of different devices are independent, and
    the broker keeps them (and any desktop call) apart. A call ends when the device
    hangs up, disconnects or is unpaired; an answer that arrives after that is
    hung up at once and never reaches the device.
    """

    def __init__(self, broker: CallBroker, admit: Callable[[object], Awaitable[Optional[str]]]):
        self._broker = broker
        self._admit = admit  # current authorization: the device's profile, or raises CallError
        self._calls: dict[str, _Call] = {}  # device id -> its call

    def active(self, device_id: str) -> bool:
        call = self._calls.get(device_id)
        return call is not None and call.ref is not None

    def owns(self, call: _Call) -> bool:
        return self._calls.get(call.session.device_id) is call and not call.ended and not call.session.closed

    async def task_call(self, session, call_id: str) -> _Call:
        """The currently authorized owner of a task request; never trust wire identity fields."""
        profile = await self._admit(session)
        call = self._calls.get(session.device_id)
        if (call is None or call.session is not session or call.id != call_id or call.ref is None
                or not self.owns(call) or profile != call.profile):
            raise CallError("not_connected", "The originating call is no longer connected. The task was not submitted.")
        return call

    async def start(self, session, msg: dict) -> None:
        call_id = str(msg.get("id") or "")[:MAX_ID]
        offer = msg.get("offer")
        if not call_id or not isinstance(offer, str) or not offer.strip() or len(offer) > MAX_OFFER_BYTES:
            await _error(session, call_id, "bad_request", "call.start needs an id and an SDP offer")
            return
        language = str(msg.get("language") or "en").strip().lower()[:2]
        if language not in LANGUAGES:
            language = "en"
        if session.device_id in self._calls:
            await _error(session, call_id, "busy", "this device already has a call; end it first")
            return
        call = _Call(call_id, session)
        self._calls[session.device_id] = call  # before any await, so a second start is busy
        try:
            profile = await self._admit(session)
            call.profile = profile
        except CallError as exc:
            self._drop(call)
            await _error(session, call_id, exc.code, exc.message)
            return
        log.info("[%s] call %s starting", session.device_id, call_id)
        loop = asyncio.get_running_loop()
        attempt = loop.run_in_executor(None, lambda: self._broker.start(profile=profile, offer=offer,
                                                                        language=language))
        try:
            ref, answer = await asyncio.wait_for(asyncio.shield(attempt), START_TIMEOUT_S)
        except CallError as exc:
            self._drop(call)
            if not call.ended:
                log.info("[%s] call %s failed: %s %s", session.device_id, call_id, exc.code, exc.message)
                await _error(session, call_id, exc.code, exc.message)
            return
        except (asyncio.TimeoutError, asyncio.CancelledError) as exc:
            self._drop(call)
            attempt.add_done_callback(self._hang_up_late)
            if isinstance(exc, asyncio.CancelledError):
                raise
            if not call.ended:
                await _error(session, call_id, "live_timeout", "the voice service did not answer in time")
            return
        if call.ended or session.closed or not await self._still_admitted(session):
            self._drop(call)
            log.info("[%s] call %s ended before it was answered; hanging up", session.device_id, call_id)
            await asyncio.to_thread(self._broker.stop, ref)
            return
        call.ref = ref
        log.info("[%s] call %s answered", session.device_id, call_id)
        await session.send_json(protocol.message("call.answer", id=call_id, answer=answer))

    async def stop(self, session, msg: dict) -> None:
        call_id = str(msg.get("id") or "")[:MAX_ID]
        call = self._calls.get(session.device_id)
        if call is None or call.id != call_id:
            # Nothing of this device's by that id is live: already ended, or never existed.
            await session.send_json(protocol.message("call.ended", id=call_id, reason="not_found"))
            return
        await self._end(call, "hangup")
        await session.send_json(protocol.message("call.ended", id=call_id, reason="hangup"))

    async def end_device(self, session, reason: str, *, notify: bool = True) -> None:
        """End ``session``'s call (unpaired, disconnected); a start in progress is hung up when it returns."""
        call = self._calls.get(session.device_id)
        if call is None or call.session is not session:
            return
        await self._end(call, reason)
        if notify and not session.closed:
            await session.send_json(protocol.message("call.ended", id=call.id, reason=reason))

    async def close(self) -> None:
        for call in list(self._calls.values()):
            await self._end(call, "server stopping")

    async def _end(self, call: _Call, reason: str) -> None:
        call.ended = True
        self._drop(call)
        if call.ref is None:
            log.info("[%s] call %s ended while starting (%s)", call.session.device_id, call.id, reason)
        else:
            log.info("[%s] call %s ended (%s)", call.session.device_id, call.id, reason)
            await asyncio.to_thread(self._broker.stop, call.ref)

    def _drop(self, call: _Call) -> None:
        if self._calls.get(call.session.device_id) is call:
            del self._calls[call.session.device_id]

    async def _still_admitted(self, session) -> bool:
        try:
            await self._admit(session)
        except CallError:
            return False
        return True

    def _hang_up_late(self, attempt: asyncio.Future) -> None:
        if attempt.cancelled() or attempt.exception() is not None:
            return
        ref, _ = attempt.result()
        log.info("call %s answered after its device gave up; hanging up", ref)
        asyncio.get_running_loop().run_in_executor(None, self._broker.stop, ref)


async def _error(session, call_id: str, code: str, message: str) -> None:
    await session.send_json(protocol.message("call.error", id=call_id, code=code, message=message))
