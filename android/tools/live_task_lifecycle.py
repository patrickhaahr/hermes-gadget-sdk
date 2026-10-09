#!/usr/bin/env python3
"""Run the opt-in phone lifecycle check and confirm real Hermes task completion.

Run on the paired Hermes host, with adb on PATH and the debug/test APKs installed.
Only generated test markers and message ids are printed; no conversation content.
"""

from __future__ import annotations

import os
import re
import socket
import sqlite3
import subprocess
import threading
from pathlib import Path


def main() -> int:
    db = Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes")) / "state.db"
    with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as conn:
        baseline = conn.execute("SELECT COALESCE(MAX(id), 0) FROM messages").fetchone()[0]
    subprocess.run(["adb", "shell", "setprop", "debug.hg7.completed", "none"], check=True)
    stop = threading.Event()
    errors: list[Exception] = []
    completed: set[str] = set()

    def observe() -> None:
        try:
            while not stop.wait(0.5):
                with sqlite3.connect(f"file:{db}?mode=ro", uri=True) as conn:
                    rows = conn.execute(
                        "SELECT m.id, m.role, m.tool_name, m.content FROM messages m "
                        "JOIN sessions s ON s.id = m.session_id "
                        "WHERE m.id > ? AND s.source = 'gadget' ORDER BY m.id", (baseline,)).fetchall()
                terminals: set[str] = set()
                for msg_id, role, tool, content in rows:
                    markers = set(re.findall(r"hg7-[0-9a-f-]{36}", content or ""))
                    if socket.gethostname() not in (content or ""):
                        continue
                    if role == "tool" and tool == "terminal":
                        terminals.update(markers)
                    elif role == "assistant":
                        for marker in (markers & terminals) - completed:
                            subprocess.run(["adb", "shell", "setprop", "debug.hg7.completed", marker], check=True)
                            completed.add(marker)
                            print(f"Stored terminal result and final answer: {marker}, message {msg_id}", flush=True)
        except Exception as exc:
            errors.append(exc)

    watcher = threading.Thread(target=observe, daemon=True)
    watcher.start()
    try:
        result = subprocess.run([
            "adb", "shell", "am", "instrument", "-w", "-e", "liveLifecycle", "true", "-e", "class",
            "io.github.adolanium.hermesgadget.LiveTaskLifecycleDeviceTest",
            "io.github.adolanium.hermesgadget.test/androidx.test.runner.AndroidJUnitRunner",
        ], capture_output=True, text=True, timeout=900)
        print(result.stdout)
        if result.stderr:
            print(result.stderr)
        if errors:
            raise errors[0]
        return 0 if result.returncode == 0 and "OK (1 test)" in result.stdout and len(completed) == 4 else 1
    finally:
        stop.set()
        watcher.join(timeout=5)
        subprocess.run(["adb", "shell", "setprop", "debug.hg7.completed", '""'], check=True)


if __name__ == "__main__":
    raise SystemExit(main())
