"""Hermes gateway platform adapter for Hermes Gadget devices.

Each connected device is a direct-message chat whose ``chat_id`` and
``user_id`` are the device id. Everything Hermes already does for messaging
platforms applies unchanged: voice notes are transcribed by the gateway's STT,
replies are spoken through auto-TTS (streaming PCM when the TTS provider
supports it), unknown devices go through Hermes's DM pairing codes, and
``/stop`` interrupts a running turn. Confirmations (``/new``, dangerous
commands) appear on the device as yes/no questions answered with its buttons.

config.yaml::

    platforms:
      gadget:
        enabled: true
        extra:
          host: 0.0.0.0          # interface devices connect to
          port: 8765
          path: /gadget
          speak_replies: true    # auto-TTS for devices with a speaker
          auto_home: true        # first approved device becomes the gadget home channel
          unauthorized_dm_behavior: pair
          live_calls: false      # paired phones may start subscription GPT-Live calls (needs
                                 # the Hermes Live Voice plugin; see docs/android.md)
          live_voice_plugin: ~/.hermes/plugins/talk-desktop   # where that plugin is installed

Secrets (``~/.hermes/.env``): ``GADGET_ACCESS_TOKEN`` (optional shared token).
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import ssl
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from agent.i18n import t
from gateway.config import Platform, PlatformConfig
from gateway.platforms._shared import extra_or_secret, get_scoped_secret as _get_scoped_secret
from gateway.platforms.base import (
    AudioFormat,
    BasePlatformAdapter,
    ExecApprovalPrompt,
    SendResult,
    StreamingTTSHandle,
    cache_audio_from_bytes_async,
    cache_image_from_url,
)
from gateway.platforms.event import MessageEvent, MessageType

from . import audio as gaudio
from . import protocol
from . import calls, imaging, ota, runtime, textfmt
from .hub import DeviceHub, DeviceSession, HubDelegate
from .store import DeviceStore

logger = logging.getLogger(__name__)

PLATFORM_NAME = "gadget"
MAX_MESSAGE_LENGTH = 4000
PAIRING_TTL_S = 3600          # mirrors Hermes's pairing-code lifetime
PAIRING_POLL_S = 2.0
PAIRING_GRACE_S = 6.0
UPDATE_POLL_S = 3.0           # how often staged firmware ('hermes gadget update') is looked for
# Trigger text for Hermes's unauthorized-DM flow. Never reaches the agent while the
# device is unpaired; if approval races it, it is a cheap read-only command.
PAIRING_TRIGGER = "/status"
_PAIRING_CMD = re.compile(r"(hermes\s+(?:-p\s+\S+\s+)?pairing\s+approve\s+\S+\s+([A-Z2-9]{8}))\b")
# The gateway echoes speech-to-text results with this translated line: a microphone emoji and the
# quoted transcript, quoted per language (Ukrainian uses «»). Patterns are built from the line itself.
_ECHO_KEY = "gateway.voice.transcript_echo_short"
_ECHO_SLOT = "\x00"
_echo_patterns: Dict[str, Optional[re.Pattern]] = {}
# Holding CANCEL already is a deliberate "start over", so the /new it sends confirms itself.
AUTO_CONFIRM_NEW_S = 15.0
PROMPT_PREFIX = "prompt:"  # message ids of device questions, so edits can withdraw them
SLASH_CONFIRM_TTL_S = 300  # mirrors tools.slash_confirm.DEFAULT_TIMEOUT_SECONDS
# Hermes words confirmations for chat apps: a bold header and the detail, then a
# list of choices and an italic note on typed replies. A device keeps the first two.
_CHOICE_LIST = re.compile(r"^\s*[•*-]\s", re.MULTILINE)
_ITALIC_NOTE = re.compile(r"^_.*_$", re.DOTALL)


def _flag(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in ("1", "true", "yes", "on")


@dataclass
class GadgetTTSHandle(StreamingTTSHandle):
    out: Any = None


@dataclass
class _Prompt:
    """A question shown on a device, and how to hand its answer back to Hermes."""
    id: str
    kind: str  # "slash" (tools.slash_confirm) or "approval" (tools.approval)
    session_key: str
    title: str
    text: str
    confirm_id: str = ""
    ttl_s: Optional[float] = None
    created: float = field(default_factory=time.monotonic)

    def expired(self) -> bool:
        return self.kind == "slash" and time.monotonic() - self.created > SLASH_CONFIRM_TTL_S


@dataclass
class _VoiceTask:
    call: calls._Call
    receipt: dict
    result: str = ""


def transcript_echo(content: str) -> Optional[str]:
    """The transcript, if ``content`` is the gateway's echo line in Hermes's active language."""
    line = t(_ECHO_KEY, text=_ECHO_SLOT)
    if line not in _echo_patterns:
        head, slot, tail = line.partition(_ECHO_SLOT)
        head, tail = head.strip(), tail.strip()
        _echo_patterns[line] = (re.compile(rf"^\s*{re.escape(head)}\s*(.*?)\s*{re.escape(tail)}\s*$", re.DOTALL)
                                if slot and (head or tail) else None)
    match = _echo_patterns[line].match(content) if _echo_patterns[line] else None
    return match.group(1) if match else None


def confirm_text(title: str, message: str, charset: str = "ascii") -> Tuple[str, str]:
    """Reduce a Hermes confirmation to a device question: (headline, detail)."""
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", message or "") if p.strip()]
    kept = [p for p in paragraphs if not _CHOICE_LIST.search(p) and not _ITALIC_NOTE.match(p)]
    head = textfmt.for_device(kept.pop(0), charset) if kept else ""
    detail = textfmt.for_device(" ".join(kept), charset)
    return head or textfmt.for_device(title, charset), detail


class GadgetAdapter(BasePlatformAdapter, HubDelegate):
    MAX_MESSAGE_LENGTH = MAX_MESSAGE_LENGTH
    supports_status_text = True
    supports_call_tasks = True

    def __init__(self, config: PlatformConfig):
        super().__init__(config=config, platform=Platform(PLATFORM_NAME))
        extra = config.extra or {}
        self._host = str(extra.get("host") or "0.0.0.0")
        port = extra.get("port")
        self._port = 8765 if port in (None, "") else int(port)  # 0 = any free port
        self._path = str(extra.get("path") or "/gadget")
        self._heartbeat_s = int(extra.get("heartbeat_s") or 20)
        self._max_utterance_s = float(extra.get("max_utterance_s") or 60)
        self._speak_replies = _flag(extra.get("speak_replies"), True)
        self._auto_home = _flag(extra.get("auto_home"), True)
        self._tls_cert = extra.get("tls_cert")
        self._tls_key = extra.get("tls_key")
        self._access_token = extra_or_secret(extra, "access_token", "GADGET_ACCESS_TOKEN") or None
        self._live_calls = _flag(extra.get("live_calls"), False)
        self._live_voice_plugin = extra.get("live_voice_plugin")
        self._hub: Optional[DeviceHub] = None
        self._store: Optional[DeviceStore] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._turns: Dict[str, str] = {}
        self._voice_tasks: Dict[str, _VoiceTask] = {}  # accepted work outlives its call and device connection
        self._watch_task: Optional[asyncio.Task] = None
        self._update_task: Optional[asyncio.Task] = None
        self._installing: Dict[str, asyncio.Task] = {}  # device id -> staged update being installed
        self._registry_key = "default"
        self._prompts: Dict[str, List[_Prompt]] = {}  # device id -> questions, oldest (shown) first
        self._new_requested: Dict[str, float] = {}  # device id -> when it asked for a new session

    # -- lifecycle ------------------------------------------------------------------

    def _ssl_context(self) -> Optional[ssl.SSLContext]:
        if not self._tls_cert:
            return None
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(self._tls_cert, self._tls_key or None)
        return ctx

    async def connect(self, *, is_reconnect: bool = False) -> bool:
        from plugins.plugin_storage import plugin_data_dir

        self._store = DeviceStore(plugin_data_dir(PLATFORM_NAME))
        try:
            ssl_ctx = self._ssl_context()
        except (OSError, ssl.SSLError) as exc:
            self._set_fatal_error("gadget_tls", f"Cannot load TLS certificate: {exc}", retryable=False)
            return False
        self._hub = DeviceHub(
            self._store, self, host=self._host, port=self._port, path=self._path,
            access_token=self._access_token, heartbeat_s=self._heartbeat_s, ssl_context=ssl_ctx,
            max_utterance_s=self._max_utterance_s, call_broker=self._call_broker())
        try:
            await self._hub.start()
        except OSError as exc:
            self._hub = None
            self._set_fatal_error(
                "gadget_listen_failed", f"Cannot listen on {self._host}:{self._port}: {exc}", retryable=True)
            return False
        self._loop = asyncio.get_running_loop()
        self._registry_key = getattr(self, "_owner_profile", None) or "default"
        runtime.register(self._registry_key, self)
        self._watch_task = asyncio.create_task(self._watch_pairing())
        self._update_task = asyncio.create_task(self._watch_updates())
        self._mark_connected()
        self._wire_plugin_handlers(None)
        logger.info("[%s] devices connect to %s://<this host>:%s%s", self.name,
                    "wss" if ssl_ctx else "ws", self._hub.bound_port, self._hub.path)
        return True

    def _call_broker(self) -> Optional[calls.CallBroker]:
        """The Live Voice broker when ``live_calls`` is on; without it, devices are told calls are unsupported."""
        if not self._live_calls:
            return None
        if self._live_voice_plugin:
            plugin_dir = Path(str(self._live_voice_plugin)).expanduser()
        else:
            try:
                from hermes_constants import get_process_hermes_home
                home = get_process_hermes_home()
            except ImportError:
                home = Path(os.environ.get("HERMES_HOME") or Path.home() / ".hermes")
            plugin_dir = home / "plugins" / "talk-desktop"
        try:
            broker = calls.load_live_voice(plugin_dir)
        except calls.CallError as exc:
            logger.warning("[%s] live calls are off: %s", self.name, exc.message)
            return None
        logger.info("[%s] live calls enabled through %s", self.name, plugin_dir)
        return broker

    async def disconnect(self) -> None:
        runtime.unregister(self._registry_key, self)
        for task in (self._watch_task, self._update_task):
            if task:
                task.cancel()
        self._watch_task = self._update_task = None
        if self._hub:
            await self._hub.stop()
            self._hub = None
        self._mark_disconnected()

    @property
    def hub(self) -> Optional[DeviceHub]:
        return self._hub

    @property
    def loop(self) -> Optional[asyncio.AbstractEventLoop]:
        return self._loop

    def _session(self, chat_id: str) -> Optional[DeviceSession]:
        return self._hub.get(str(chat_id)) if self._hub else None

    # -- pairing --------------------------------------------------------------------

    def _authorized(self, device_id: str) -> Optional[bool]:
        return self._is_sender_authorized(device_id, "dm", device_id)

    async def is_paired(self, session: DeviceSession) -> bool:
        # None means no runner check is installed (unit harness): nothing to gate on.
        return self._authorized(session.device_id) is not False

    async def call_profile(self, session: DeviceSession) -> Optional[str]:
        # A call spends the host's voice allowance: only an explicit, current approval admits it.
        if self._authorized(session.device_id) is not True:
            raise calls.CallError("not_paired", "this device is not paired with Hermes")
        return getattr(self, "_owner_profile", None)

    async def on_ready(self, session: DeviceSession) -> None:
        if session.paired:
            self._store.clear_pairing(session.device_id)
            await self._claim_home(session)
            await self._show_prompt(session)  # a question asked while it was away
            return
        cached = self._store.pairing_for(session.device_id)
        if cached:
            await session.send_pairing(*cached)
            return
        # Hermes answers an unauthorized DM with a pairing code (rate limited per sender);
        # send() recognises it and forwards it to the device as a pairing frame.
        await self.handle_message(MessageEvent(
            text=PAIRING_TRIGGER, message_type=MessageType.TEXT,
            source=self._source(session), message_id=self._message_id(session, "pair")))
        session.spawn(self._pairing_grace(session))

    async def _pairing_grace(self, session: DeviceSession) -> None:
        await asyncio.sleep(PAIRING_GRACE_S)
        if session.closed or session.paired or self._store.pairing_for(session.device_id):
            return
        await session.send_notice(
            f"No pairing code yet. On the Hermes host run 'hermes pairing list', or allow "
            f"{session.device_id} in GADGET_ALLOWED_USERS.", ttl_s=60)

    async def _watch_pairing(self) -> None:
        """Mirror pairing approvals and revocations onto connected devices."""
        while True:
            await asyncio.sleep(PAIRING_POLL_S)
            if not self._hub:
                continue
            for session in list(self._hub.sessions.values()):
                verdict = self._authorized(session.device_id)
                if verdict is None:
                    continue
                try:
                    if verdict and not session.paired:
                        self._store.clear_pairing(session.device_id)
                        await session.set_paired(True)
                        logger.info("[%s] device %s approved", self.name, session.device_id)
                        await self._claim_home(session)
                    elif not verdict and session.paired:
                        await session.set_paired(False)
                        await self.on_ready(session)
                except Exception as exc:
                    logger.debug("[%s] pairing sync failed for %s: %s", self.name, session.device_id, exc)

    # -- firmware updates -------------------------------------------------------------

    async def _watch_updates(self) -> None:
        """Install firmware staged with 'hermes gadget update' once its device is online."""
        queue = ota.UpdateQueue(self._store.path.parent)
        while True:
            await asyncio.sleep(UPDATE_POLL_S)
            if not self._hub:
                continue
            for device_id in queue.pending():
                session = self._hub.get(device_id)
                running = self._installing.get(device_id)
                if session is None or session.closed or (running and not running.done()):
                    continue
                self._installing[device_id] = session.spawn(self._install_staged(queue, session))

    async def _install_staged(self, queue: ota.UpdateQueue, session: DeviceSession) -> None:
        staged = queue.load(session.device_id)
        if staged is None:
            return
        data, _meta = staged
        reported = 0.0

        def progress(sent: int, total: int) -> None:
            nonlocal reported
            if sent == total or time.monotonic() - reported >= 1.0:
                reported = time.monotonic()
                queue.report(session.device_id, state="sending", sent=sent, size=total)

        logger.info("[%s] installing staged firmware on %s", self.name, session.device_id)
        try:
            version = await session.update_firmware(data, progress=progress)
        except ota.UpdateError as exc:
            # A device that dropped off gets the image again when it's back, a few times.
            retry = exc.code in ota.RETRY_CODES and queue.count_attempt(session.device_id) < ota.MAX_ATTEMPTS
            # Drop before the final report: whoever reads "failed" or "done" finds nothing staged.
            if not retry:
                queue.drop(session.device_id)
            queue.report(session.device_id, state="retrying" if retry else "failed", code=exc.code, error=exc.message)
            logger.warning("[%s] firmware update for %s failed: %s", self.name, session.device_id, exc.message)
            return
        queue.drop(session.device_id)
        queue.report(session.device_id, state="done", version=version)
        logger.info("[%s] %s installed firmware %s", self.name, session.device_id, version)

    async def _claim_home(self, session: DeviceSession) -> None:
        """Make the first approved device the platform's home channel.

        Without one, Hermes opens every new session with a "type /sethome" notice, which a
        device without a keyboard cannot act on. This does what /sethome would: the running
        config is updated (the runner reads the same object) and config.yaml keeps it.
        """
        if not self._auto_home or self.config.home_channel is not None:
            return
        if str(_get_scoped_secret("GADGET_HOME_CHANNEL", "") or "").strip():
            return
        from gateway import config as gateway_config

        home = gateway_config.HomeChannel(platform=self.platform, chat_id=session.device_id,
                                          name=session.name, user_id=session.device_id)
        self.config.home_channel = home
        try:
            await asyncio.to_thread(gateway_config.persist_home_channel, home)
        except Exception as exc:  # the running gateway still has it; only a restart forgets
            logger.warning("[%s] could not save home channel %s: %s", self.name, session.device_id, exc)
            return
        logger.info("[%s] %s (%s) is now the gadget home channel", self.name, session.name, session.device_id)

    async def _forward_unpaired(self, session: DeviceSession, content: str) -> None:
        match = _PAIRING_CMD.search(content or "")
        if match:
            command, code = match.group(1), match.group(2)
            self._store.remember_pairing(session.device_id, code, command, PAIRING_TTL_S)
            await session.send_pairing(code, command)
        else:
            await session.send_notice(textfmt.for_device(content, session.charset), ttl_s=30)

    # -- inbound --------------------------------------------------------------------

    def _source(self, session: DeviceSession):
        return self.build_source(
            chat_id=session.device_id, chat_name=session.name, chat_type="dm",
            user_id=session.device_id, user_name=session.name)

    @staticmethod
    def _message_id(session: DeviceSession, msg_id: str) -> str:
        return f"{session.session_id}:{msg_id or uuid.uuid4().hex[:8]}"

    def _event(self, session: DeviceSession, msg_id: str, text: str, **kw) -> MessageEvent:
        return MessageEvent(
            text=text, source=self._source(session), message_id=self._message_id(session, msg_id),
            user_id=session.device_id, user_name=session.name, channel_prompt=session.prompt_context(), **kw)

    async def on_text(self, session: DeviceSession, msg_id: str, text: str) -> None:
        await self.handle_message(self._event(session, msg_id, text, message_type=MessageType.TEXT))

    async def on_call_task(self, session: DeviceSession, msg: dict) -> None:
        call_id, delegation, text = msg.get("id"), msg.get("delegation"), msg.get("text")
        receipt = protocol.message("call.task.status", id=call_id, delegation=delegation)

        async def refuse(state: str, reason: str) -> None:
            await session.send_json(dict(receipt, state=state, text=reason))

        if (not isinstance(call_id, str) or not 0 < len(call_id) <= calls.MAX_ID
                or not isinstance(delegation, str) or not 0 < len(delegation) <= calls.MAX_ID
                or not isinstance(text, str) or not text.strip() or len(text) > MAX_MESSAGE_LENGTH):
            await refuse("failed", "The task request is invalid or too long. Nothing was submitted.")
            return
        if self._hub.calls is None:
            await refuse("failed", "This host does not support voice tasks.")
            return
        try:
            call = await self._hub.calls.task_call(session, call_id)
        except calls.CallError as exc:
            await refuse("not_connected", exc.message)
            return
        if delegation in call.delegations:
            await session.send_json(call.delegations[delegation])
            return
        # Bound retained receipts without evicting ids: eviction could execute an old request again.
        if len(call.delegations) >= 256:
            await refuse("failed", "This call has reached its task request limit. Start a new call.")
            return
        event = self._event(session, "voice-" + uuid.uuid4().hex, text.strip(),
                            message_type=MessageType.TEXT, allow_gateway_control=False)
        # Hermes's adapter owns the guard for this exact persistent gadget session.
        # Checking before handle_message avoids its ordinary interrupt/queue policy.
        if session.device_id in self._voice_tasks or self._event_session_key(event) in self._active_sessions:
            receipt.update(state="busy", text="A Hermes task is still running. Please wait for it to finish, then ask again. Nothing was queued.")
            call.delegations[delegation] = receipt
            await session.send_json(receipt)
            return
        receipt.update(state="accepted", turn=event.message_id)
        task = _VoiceTask(call, receipt)
        call.delegations[delegation] = receipt
        self._voice_tasks[session.device_id] = task  # reserve before the first submission await
        try:
            await self.handle_message(event)
            if not getattr(event, "_gateway_accepted", False):
                raise RuntimeError("Hermes did not accept the task")
        except Exception:
            logger.exception("[%s] voice task submission failed", self.name)
            self._voice_tasks.pop(session.device_id, None)
            receipt.update(state="failed", text="Hermes could not accept the task. It will not be retried automatically.")
        await session.send_json(receipt)

    async def on_utterance(self, session: DeviceSession, msg_id: str, wav: bytes, seconds: float) -> None:
        path = await cache_audio_from_bytes_async(wav, ".wav")
        logger.debug("[%s] %.1fs utterance from %s -> %s", self.name, seconds, session.device_id, path)
        await self.handle_message(self._event(
            session, msg_id, "", message_type=MessageType.VOICE, media_urls=[path], media_types=["audio/wav"]))

    async def on_cancel(self, session: DeviceSession) -> None:
        await self.handle_message(self._event(session, "cancel", "/stop", message_type=MessageType.TEXT))

    async def on_new_session(self, session: DeviceSession) -> None:
        self._new_requested[session.device_id] = time.monotonic()
        await self.handle_message(self._event(session, "new", "/new", message_type=MessageType.TEXT))

    async def on_event(self, session: DeviceSession, name: str, data: Any, notify: bool) -> None:
        logger.info("[%s] event from %s: %s %s", self.name, session.device_id, name, data)
        if not notify:
            return
        payload = json.dumps(data, separators=(",", ":"))[:500] if data is not None else ""
        await self.handle_message(self._event(
            session, f"ev-{uuid.uuid4().hex[:6]}", f"[Gadget event] {name} {payload}".strip(),
            message_type=MessageType.TEXT, allow_gateway_control=False))

    async def on_disconnect(self, session: DeviceSession) -> None:
        if self._hub and session.device_id not in self._hub.sessions and session.device_id not in self._voice_tasks:
            self._turns.pop(session.device_id, None)

    # -- turn lifecycle -------------------------------------------------------------

    async def on_processing_start(self, event: MessageEvent) -> None:
        turn = event.message_id or uuid.uuid4().hex[:12]
        self._turns[event.source.chat_id] = turn
        session = self._session(event.source.chat_id)
        if session is None or not session.paired:
            return
        await session.turn_start(turn)

    async def on_processing_complete(self, event: MessageEvent, outcome) -> None:
        chat_id = event.source.chat_id
        state = getattr(outcome, "value", str(outcome))
        task = self._voice_tasks.get(chat_id)
        if task is not None and task.receipt["turn"] == event.message_id:
            self._voice_tasks.pop(chat_id)
            task.receipt.update(state="completed" if state == "success" else "failed",
                                text=task.result or "The Hermes task ended without a result.")
            if self._hub and self._hub.calls and self._hub.calls.owns(task.call):
                try:
                    await self.call_profile(task.call.session)
                    await task.call.session.send_json(task.receipt)
                except Exception:
                    pass  # keep the result in Hermes; never deliver to a new call
        turn = self._turns.pop(chat_id, None) or event.message_id or ""
        session = self._session(event.source.chat_id)
        if session is None or not session.paired:
            return
        await session.turn_end(turn, state)

    def set_status_text(self, chat_id: str, text: Optional[str]) -> None:
        super().set_status_text(chat_id, text)
        session = self._session(chat_id)
        if session is None or self._loop is None:
            return
        coro = session.send_status(text or "")
        try:
            running = asyncio.get_running_loop()
        except RuntimeError:
            running = None
        if running is self._loop:
            session.spawn(coro)
        else:
            asyncio.run_coroutine_threadsafe(coro, self._loop)

    # -- outbound text ----------------------------------------------------------------

    async def send(self, chat_id: str, content: str, reply_to: Optional[str] = None,
                   metadata: Optional[Dict[str, Any]] = None) -> SendResult:
        task = self._voice_tasks.get(chat_id)
        if task is not None and not (metadata or {}).get("_interim_send"):
            task.result += content + "\n"
        session = self._session(chat_id)
        if session is None:
            if task is not None:
                return SendResult(success=True)  # stored by Hermes; deliberately silent after disconnect
            return SendResult(success=False, error=f"gadget {chat_id} is not connected")
        if not session.paired:
            if task is not None:
                return SendResult(success=True)  # revoked devices get no task content
            await self._forward_unpaired(session, content)
            return SendResult(success=True, message_id=uuid.uuid4().hex[:12])
        heard = transcript_echo(content or "")
        if heard is not None:
            # Show what speech-to-text heard as the user's line, not as a reply.
            await session.send_transcript(textfmt.for_device(heard, session.charset))
            return SendResult(success=True, message_id=uuid.uuid4().hex[:12])
        text = textfmt.for_device(content, session.charset)
        if text:
            interim = bool((metadata or {}).get("_interim_send"))
            await session.send_reply(text, turn=self._turns.get(session.device_id), interim=interim)
        return SendResult(success=True, message_id=uuid.uuid4().hex[:12])

    def supports_draft_streaming(self, chat_type=None, metadata=None, chat_id=None) -> bool:
        return True

    async def send_draft(self, chat_id: str, draft_id: int, content: str,
                         metadata: Optional[Dict[str, Any]] = None) -> SendResult:
        session = self._session(chat_id)
        if session is None or not session.paired:
            return SendResult(success=False, error="gadget not connected")
        await session.send_delta(textfmt.for_device(content, session.charset), turn=self._turns.get(session.device_id))
        return SendResult(success=True)

    async def edit_message(self, chat_id: str, message_id: str, content: str, *, finalize: bool = False) -> SendResult:
        task = self._voice_tasks.get(chat_id)
        if task is not None and finalize and not str(message_id).startswith(PROMPT_PREFIX):
            task.result = content
        session = self._session(chat_id)
        if session is None or not session.paired:
            if task is not None:
                return SendResult(success=True)
            return SendResult(success=False, error="gadget not connected")
        text = textfmt.for_device(content, session.charset)
        if str(message_id).startswith(PROMPT_PREFIX):
            # Hermes edits a question card when it times out: withdraw it and say why.
            await self._withdraw_prompt(session, str(message_id)[len(PROMPT_PREFIX):])
            if text:
                await session.send_notice(text, ttl_s=15)
            return SendResult(success=True, message_id=message_id)
        turn = self._turns.get(session.device_id)
        if finalize:
            await session.send_reply(text, turn=turn)
        else:
            await session.send_delta(text, turn=turn)
        return SendResult(success=True, message_id=message_id)

    # -- questions: slash-command confirmations and command approvals -----------------

    async def send_slash_confirm(self, chat_id: str, title: str, message: str, session_key: str,
                                 confirm_id: str, metadata: Optional[Dict[str, Any]] = None) -> SendResult:
        session = self._session(chat_id)
        if session is None or not session.paired:
            return SendResult(success=False, error="gadget not connected")
        from tools import slash_confirm

        pending = slash_confirm.get_pending(session_key) or {}
        requested = self._new_requested.pop(session.device_id, None)
        if (pending.get("command") == "new" and pending.get("confirm_id") == confirm_id
                and requested is not None and time.monotonic() - requested <= AUTO_CONFIRM_NEW_S):
            # The device asked for this /new itself (CANCEL held for two seconds).
            session.spawn(self._answer_slash(session, session_key, confirm_id, "once"))
            return SendResult(success=True, message_id=uuid.uuid4().hex[:12])
        head, detail = confirm_text(title, message, session.charset)
        prompt = _Prompt(id=uuid.uuid4().hex[:8], kind="slash", session_key=session_key, title=head,
                         text=detail, confirm_id=confirm_id, ttl_s=SLASH_CONFIRM_TTL_S)
        return await self._ask(session, prompt)

    async def _send_exec_approval_prompt(self, prompt: ExecApprovalPrompt) -> SendResult:
        session = self._session(prompt.chat_id)
        if session is None or not session.paired:
            return SendResult(success=False, error="gadget not connected")
        text = prompt.command.strip()
        if prompt.description:
            text = f"{text}\n{prompt.description}"
        question = _Prompt(id=uuid.uuid4().hex[:8], kind="approval", session_key=prompt.session_key,
                           title="Allow this command?", text=textfmt.for_device(text, session.charset))
        return await self._ask(session, question)

    async def _ask(self, session: DeviceSession, prompt: _Prompt) -> SendResult:
        queue = self._prompts.setdefault(session.device_id, [])
        if prompt.kind == "slash":
            # Hermes keeps one confirmation per session: a new one supersedes the last.
            queue[:] = [p for p in queue if not (p.kind == "slash" and p.session_key == prompt.session_key)]
        queue.append(prompt)
        await self._show_prompt(session)
        return SendResult(success=True, message_id=PROMPT_PREFIX + prompt.id)

    async def _show_prompt(self, session: DeviceSession) -> None:
        """Put the oldest open question on the device (it shows one at a time)."""
        queue = self._prompts.get(session.device_id) or []
        queue[:] = [p for p in queue if not p.expired()]
        if queue:
            head = queue[0]
            await session.ask(head.id, head.title, head.text, ttl_s=head.ttl_s)

    def _take_prompt(self, device_id: str, prompt_id: str) -> Optional[_Prompt]:
        queue = self._prompts.get(device_id) or []
        for i, prompt in enumerate(queue):
            if prompt.id == prompt_id:
                return queue.pop(i)
        return None

    async def _withdraw_prompt(self, session: DeviceSession, prompt_id: str) -> None:
        queue = self._prompts.get(session.device_id) or []
        was_shown = bool(queue) and queue[0].id == prompt_id
        if self._take_prompt(session.device_id, prompt_id) and was_shown:
            await session.close_prompt(prompt_id)
            await self._show_prompt(session)

    async def on_prompt_reply(self, session: DeviceSession, prompt_id: str, yes: bool) -> None:
        prompt = self._take_prompt(session.device_id, prompt_id)
        if prompt is None or prompt.expired():
            await session.send_notice("That question has expired")
        elif prompt.kind == "slash":
            await self._answer_slash(session, prompt.session_key, prompt.confirm_id, "once" if yes else "cancel")
        else:
            from tools.approval import resolve_gateway_approval

            if resolve_gateway_approval(prompt.session_key, "once" if yes else "deny"):
                await session.send_notice("Approved" if yes else "Denied")
            else:
                await session.send_notice("That approval has expired")
        await self._show_prompt(session)

    async def _answer_slash(self, session: DeviceSession, session_key: str, confirm_id: str, choice: str) -> None:
        from tools import slash_confirm

        try:
            result = await slash_confirm.resolve(session_key, confirm_id, choice)
        except Exception as exc:  # the handler already logs; keep the device informed
            logger.warning("[%s] confirmation failed for %s: %s", self.name, session.device_id, exc)
            result = None
        if result:
            await self.send(session.device_id, result)
        else:
            await session.send_notice("That question has expired")

    async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
        session = self._session(chat_id)
        return {"name": session.name if session else chat_id, "type": "dm", "chat_id": chat_id}

    # -- outbound audio ---------------------------------------------------------------

    def _should_auto_tts_for_chat(self, chat_id: str) -> bool:
        # Speaking devices answer voice with voice by default; an explicit
        # "/voice off" in this chat still wins.
        if chat_id in self._voice_tasks or chat_id in self._auto_tts_disabled_chats:
            return False
        session = self._session(chat_id)
        if session is not None and session.has_speaker and self._speak_replies:
            return True
        return super()._should_auto_tts_for_chat(chat_id)

    async def _play_file(self, chat_id: str, path: str) -> SendResult:
        if chat_id in self._voice_tasks:
            return SendResult(success=True)  # Live owns speech, including when its call has ended
        session = self._session(chat_id)
        if session is None or not session.paired:
            return SendResult(success=False, error="gadget not connected")
        if not session.has_speaker:
            return SendResult(success=False, error="gadget has no speaker")
        try:
            pcm = await asyncio.to_thread(gaudio.decode_file, path, session.speaker_rate)
        except Exception as exc:  # undecodable file, missing ffmpeg, I/O error
            logger.warning("[%s] cannot play %s: %s", self.name, path, exc)
            return SendResult(success=False, error=str(exc))
        await session.play_pcm(pcm, session.speaker_rate, turn=self._turns.get(session.device_id))
        return SendResult(success=True, message_id=uuid.uuid4().hex[:12])

    async def play_tts(self, chat_id: str, audio_path: str, **kwargs) -> SendResult:
        return await self._play_file(chat_id, audio_path)

    async def send_voice(self, chat_id: str, audio_path: str, caption: Optional[str] = None,
                         reply_to: Optional[str] = None, metadata: Optional[Dict[str, Any]] = None,
                         **kwargs) -> SendResult:
        result = await self._play_file(chat_id, audio_path)
        if caption:
            await self.send(chat_id, caption, metadata=metadata)
        return result

    def supports_streaming_tts(self, chat_id: str, audio_format: AudioFormat) -> bool:
        session = self._session(chat_id)
        return bool(chat_id not in self._voice_tasks and session and session.paired and session.has_speaker
                    and audio_format.sample_width == 2 and audio_format.channels in (1, 2))

    async def begin_streaming_tts(self, chat_id: str, audio_format: AudioFormat,
                                  metadata: Optional[Dict[str, Any]] = None) -> Optional[StreamingTTSHandle]:
        session = self._session(chat_id)
        if session is None or not self.supports_streaming_tts(chat_id, audio_format):
            return None
        out = await session.open_audio(audio_format.sample_rate, audio_format.channels,
                                       turn=self._turns.get(session.device_id))
        return GadgetTTSHandle(chat_id=chat_id, audio_format=audio_format, out=out)

    async def write_streaming_tts(self, handle: StreamingTTSHandle, chunk: bytes) -> None:
        if not handle.aborted and isinstance(handle, GadgetTTSHandle):
            handle.out.write(chunk)

    async def finish_streaming_tts(self, handle: StreamingTTSHandle, *, interrupted: bool = False) -> None:
        if not isinstance(handle, GadgetTTSHandle):
            return
        if interrupted:
            await handle.out.abort()
        else:
            handle.out.finish()

    async def abort_streaming_tts(self, handle: StreamingTTSHandle, error: Optional[str] = None) -> None:
        if isinstance(handle, GadgetTTSHandle) and not handle.aborted:
            handle.aborted = True
            await handle.out.abort()

    # -- outbound images ---------------------------------------------------------------

    async def send_image_file(self, chat_id: str, image_path: str, caption: Optional[str] = None,
                              reply_to: Optional[str] = None, metadata: Optional[Dict[str, Any]] = None,
                              **kwargs) -> SendResult:
        session = self._session(chat_id)
        if session is None or not session.paired:
            return SendResult(success=False, error="gadget not connected")
        box = session.image_box
        if box is None or not imaging.pillow_available():
            await session.send_notice("This gadget cannot display images")
        else:
            img = await asyncio.to_thread(imaging.to_rgb565, image_path, box[0], box[1])
            await session.show_image(img.width, img.height, img.rgb565)
        if caption:
            await self.send(chat_id, caption, metadata=metadata)
        return SendResult(success=True, message_id=uuid.uuid4().hex[:12])

    async def send_image(self, chat_id: str, image_url: str, caption: Optional[str] = None,
                         reply_to: Optional[str] = None, metadata: Optional[Dict[str, Any]] = None) -> SendResult:
        try:
            path = await cache_image_from_url(image_url)
        except Exception as exc:
            logger.info("[%s] image download failed (%s); sending caption only", self.name, exc)
            return await self.send(chat_id, caption or "(image unavailable)", metadata=metadata)
        return await self.send_image_file(chat_id, path, caption=caption, metadata=metadata)
