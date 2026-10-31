"""Parsing of ``PhonePal: <run_id> <EventKind> <json>`` logcat lines."""

from __future__ import annotations

import json
import re

from .traces import event_fields

# threadtime: "10-31 12:00:00.123  1234  1250 I PhonePal: r1 StepPlanned {...}"
# brief:      "I/PhonePal( 1234): r1 StepPlanned {...}"
_TAG_RE = re.compile(r"(?:^|[\s/])PhonePal\s*(?:\(\s*\d+\))?\s*:\s(?P<msg>.*)$")
_TIME_RE = re.compile(r"^(?:\d\d-\d\d\s+)?(?P<time>\d\d:\d\d:\d\d\.\d+)")
_KIND_RE = re.compile(r"^[A-Z][A-Za-z0-9]*$")

TERMINAL_KINDS = ("PhoneTaskFinished", "Failed")


class LogEvent:
    __slots__ = ("run_id", "kind", "data", "payload", "time")

    def __init__(self, run_id, kind, data, payload, time=None):
        self.run_id = run_id
        self.kind = kind
        self.data = data          # dict or None when the JSON is missing/truncated
        self.payload = payload    # raw text after the kind
        self.time = time          # "HH:MM:SS.mmm" from logcat, if present

    @property
    def terminal(self) -> bool:
        return self.kind in TERMINAL_KINDS

    @property
    def success(self):
        """For terminal events: True/False, or None when unknown."""
        if self.kind == "Failed":
            return False
        if self.data is not None and "success" in self.data:
            return bool(self.data["success"])
        if self.data is not None and "ok" in self.data and self.data["ok"] is not None:
            return bool(self.data["ok"])
        return None


def message_of(line: str):
    """The log message of a PhonePal-tagged logcat line, or None."""
    m = _TAG_RE.search(line.rstrip("\r\n"))
    return m.group("msg") if m else None


def parse_message(msg: str, time=None):
    parts = msg.strip().split(None, 2)
    if len(parts) < 2 or not _KIND_RE.match(parts[1]):
        return None
    payload = parts[2] if len(parts) > 2 else ""
    data = None
    if payload:
        try:
            value = json.loads(payload)
            if isinstance(value, dict):
                data = value
        except ValueError:
            pass
    return LogEvent(parts[0], parts[1], data, payload, time)


def parse_line(line: str):
    """Parse one logcat line into a LogEvent, or None if it is not an event line.

    Accepts threadtime/brief formatted lines and bare ``-v raw`` messages."""
    line = line.rstrip("\r\n")
    msg = message_of(line)
    time = None
    if msg is None:
        if line.startswith("--------- "):
            return None
        msg = line  # raw format
    else:
        m = _TIME_RE.match(line)
        if m:
            time = m.group("time")[:12]
    return parse_message(msg, time)


def format_event(ev: LogEvent) -> str:
    head = "%s %s %s" % (ev.time or "", ev.run_id, ev.kind)
    if ev.data is None:
        tail = ev.payload
    else:
        f = event_fields(ev.data)
        bits = []
        if f["step"]:
            bits.append("#" + f["step"])
        if f["action"]:
            bits.append(f["action"])
        if f["decision"]:
            bits.append("[%s]" % f["decision"])
        if f["ok"]:
            bits.append("ok=" + f["ok"])
        if f["text"]:
            bits.append("| " + f["text"])
        tail = " ".join(bits)
    return (head + "  " + tail).strip() if tail else head.strip()
