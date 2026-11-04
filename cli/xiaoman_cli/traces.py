"""Reading trace.jsonl files and rendering events as readable text."""

from __future__ import annotations

import datetime
import io
import json
import os
import unicodedata

# --------------------------------------------------------------------------- text width helpers


def display_width(s: str) -> int:
    w = 0
    for ch in s:
        if unicodedata.combining(ch):
            continue
        w += 2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1
    return w


def truncate(s: str, width: int) -> str:
    if width <= 0 or display_width(s) <= width:
        return s
    out = []
    w = 0
    for ch in s:
        cw = 2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1
        if w + cw > width - 1:
            break
        out.append(ch)
        w += cw
    return "".join(out) + "…"


def pad(s: str, width: int) -> str:
    return s + " " * max(0, width - display_width(s))


def one_line(value) -> str:
    if value is None:
        return ""
    s = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    return " ".join(s.split())


# --------------------------------------------------------------------------- summaries


def _point(p) -> str:
    if isinstance(p, dict) and "x" in p and "y" in p:
        return "(%s,%s)" % (p["x"], p["y"])
    if isinstance(p, (list, tuple)) and len(p) == 2:
        return "(%s,%s)" % (p[0], p[1])
    return one_line(p)


def _q(s) -> str:
    return '"%s"' % one_line(s)


def summarize_action(action) -> str:
    """Short human form of an Action JSON object (``type`` is the discriminator)."""
    if action is None:
        return ""
    if not isinstance(action, dict):
        return one_line(action)
    t = action.get("type", "?")
    parts = [str(t)]
    where = None
    if action.get("nodeId") is not None:
        where = "#%s" % action["nodeId"]
    elif action.get("point") is not None:
        where = _point(action["point"])
    if t in ("tap", "long_press"):
        if where:
            parts.append(where)
        if action.get("target"):
            parts.append(_q(action["target"]))
    elif t == "type":
        parts.append(_q(action.get("text", "")))
        if action.get("target"):
            parts.append("→ " + _q(action["target"]))
        elif where:
            parts.append("→ " + where)
        if action.get("submit"):
            parts.append("+submit")
    elif t == "swipe":
        parts.append("%s→%s" % (_point(action.get("from")), _point(action.get("to"))))
        if action.get("target"):
            parts.append(_q(action["target"]))
    elif t == "launch":
        parts.append(str(action.get("packageName") or action.get("package") or ""))
    elif t == "deeplink":
        parts.append(str(action.get("uri", "")))
    elif t == "wait":
        parts.append("%sms" % action.get("millis", ""))
    elif t == "ask_user":
        parts.append(_q(action.get("question", "")))
        if action.get("takeover"):
            parts.append("(takeover)")
    elif t == "finish":
        parts.append("ok" if action.get("success") else "fail")
        if action.get("summary"):
            parts.append(_q(action["summary"]))
    elif action.get("target"):
        parts.append(_q(action["target"]))
    return " ".join(p for p in parts if p)


def _decision(value) -> str:
    if value is None:
        return ""
    if isinstance(value, dict):
        verdict = value.get("verdict") or value.get("type") or value.get("kind") or ""
        reason = value.get("reason") or ""
        return ("%s: %s" % (verdict, reason)).strip(": ") if reason else str(verdict)
    return one_line(value)


def event_fields(data: dict) -> dict:
    """Normalize a trace line or a logged AgentEvent JSON into display fields."""
    step = data.get("step", data.get("stepIndex"))
    ok = data.get("ok")
    if ok is None:
        result = data.get("result")
        if isinstance(result, dict) and "ok" in result:
            ok = result.get("ok")
        elif "success" in data:
            ok = data.get("success")
    text = data.get("text")
    if text is None:
        for key in ("summary", "message", "question", "goal", "skillName", "permalink"):
            if data.get(key) is not None:
                text = data[key]
                break
    if text is None and isinstance(data.get("result"), dict):
        text = data["result"].get("message")
    return {
        "step": "" if step is None else str(step),
        "action": summarize_action(data.get("action")),
        "decision": _decision(data.get("decision")),
        "ok": "" if ok is None else ("yes" if ok else "no"),
        "text": one_line(text),
    }


def format_time(ms, tz=None) -> str:
    if not isinstance(ms, (int, float)) or isinstance(ms, bool):
        return ""
    dt = datetime.datetime.fromtimestamp(ms / 1000.0, tz)
    return dt.strftime("%H:%M:%S.") + "%03d" % (dt.microsecond // 1000)


# --------------------------------------------------------------------------- trace files


def load_trace(path: str):
    """Return the list of event dicts in a trace.jsonl. Invalid lines become
    ``{"kind": "<invalid>", "text": raw, "_line": n}``."""
    events = []
    with io.open(path, "r", encoding="utf-8", errors="replace") as f:
        for n, raw in enumerate(f, 1):
            raw = raw.strip()
            if not raw:
                continue
            try:
                data = json.loads(raw)
                if not isinstance(data, dict):
                    raise ValueError("not an object")
            except ValueError:
                data = {"kind": "<invalid>", "text": raw}
            data["_line"] = n
            events.append(data)
    return events


def resolve_trace(arg: str, local_root: str = "traces"):
    """Map ``traces show`` argument to a trace.jsonl path, or None if not found locally."""
    if os.path.isfile(arg):
        return arg
    if os.path.isdir(arg):
        candidate = os.path.join(arg, "trace.jsonl")
        return candidate if os.path.isfile(candidate) else None
    candidate = os.path.join(local_root, arg, "trace.jsonl")
    return candidate if os.path.isfile(candidate) else None


COLUMNS = ("time", "step", "kind", "action", "decision", "ok", "text")
MAX_WIDTH = {"action": 44, "decision": 32, "text": 60}


def render_table(events, tz=None, full: bool = False) -> str:
    rows = []
    for e in events:
        f = event_fields(e)
        rows.append({
            "time": format_time(e.get("time"), tz),
            "step": f["step"],
            "kind": str(e.get("kind", "")),
            "action": f["action"],
            "decision": f["decision"],
            "ok": f["ok"],
            "text": f["text"],
        })
    if not full:
        for r in rows:
            for col, w in MAX_WIDTH.items():
                r[col] = truncate(r[col], w)
    widths = {c: display_width(c) for c in COLUMNS}
    for r in rows:
        for c in COLUMNS:
            widths[c] = max(widths[c], display_width(r[c]))
    lines = []

    def emit(cells):
        lines.append("  ".join(pad(cells[c], widths[c]) for c in COLUMNS).rstrip())

    emit({c: c.upper() for c in COLUMNS})
    emit({c: "-" * widths[c] for c in COLUMNS})
    for r in rows:
        emit(r)
    return "\n".join(lines)


def trace_footer(events, trace_dir: str = None) -> str:
    """One-line outcome summary plus screenshot count."""
    parts = ["%d events" % len(events)]
    for e in reversed(events):
        if e.get("kind") == "PhoneTaskFinished":
            f = event_fields(e)
            parts.append("finished: %s" % ("success" if f["ok"] == "yes" else "failure"))
            break
        if e.get("kind") == "Failed":
            parts.append("failed")
            break
    if trace_dir and os.path.isdir(trace_dir):
        shots = [n for n in os.listdir(trace_dir) if n.startswith("step-") and n.endswith(".png")]
        if shots:
            parts.append("%d screenshots in %s" % (len(shots), trace_dir))
    return ", ".join(parts)
