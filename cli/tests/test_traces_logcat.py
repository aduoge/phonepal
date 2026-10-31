import datetime
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

from phonepal_cli.logcat import format_event, parse_line  # noqa: E402
from phonepal_cli.traces import (  # noqa: E402
    display_width, load_trace, render_table, resolve_trace, summarize_action, trace_footer, truncate,
)

TRACE_DIR = os.path.join(HERE, "fixtures", "traces", "r1")
UTC = datetime.timezone.utc


class TraceTest(unittest.TestCase):
    def test_load(self):
        events = load_trace(os.path.join(TRACE_DIR, "trace.jsonl"))
        self.assertEqual(len(events), 7)
        self.assertEqual(events[5]["kind"], "<invalid>")
        self.assertEqual(events[5]["_line"], 6)

    def test_resolve(self):
        path = os.path.join(TRACE_DIR, "trace.jsonl")
        self.assertEqual(resolve_trace(path), path)
        self.assertEqual(resolve_trace(TRACE_DIR), path)
        self.assertEqual(resolve_trace("r1", os.path.dirname(TRACE_DIR)), path)
        self.assertIsNone(resolve_trace("nope", os.path.dirname(TRACE_DIR)))

    def test_table(self):
        events = load_trace(os.path.join(TRACE_DIR, "trace.jsonl"))
        out = render_table(events, tz=UTC).splitlines()
        self.assertEqual(out[0].split(), ["TIME", "STEP", "KIND", "ACTION", "DECISION", "OK", "TEXT"])
        self.assertTrue(out[2].startswith("13:13:20.000"))
        self.assertIn("PhoneTaskStarted", out[2])
        self.assertIn("打车回家", out[2])
        self.assertIn('tap #12 "目的地输入框"', out[3])
        executed = out[4]
        self.assertIn("allow: no rule matched", executed)
        self.assertIn("yes", executed)
        self.assertIn('type "家" → "目的地"', out[5])
        self.assertIn('tap (500,900) "确认呼叫"', out[6])
        self.assertIn("ask: confirmation word", out[6])
        self.assertIn("not json", out[7])
        self.assertIn("已停在确认呼叫", out[8])
        # columns line up in display cells, CJK counted as 2
        kind_col = [display_width(line[:line.index(k)]) for line, k in
                    ((out[3], "StepPlanned"), (out[4], "StepExecuted"), (out[6], "StepBlocked"))]
        self.assertEqual(len(set(kind_col)), 1)

    def test_footer(self):
        events = load_trace(os.path.join(TRACE_DIR, "trace.jsonl"))
        self.assertEqual(trace_footer(events, TRACE_DIR),
                         "7 events, finished: success, 1 screenshots in %s" % TRACE_DIR)

    def test_truncate(self):
        self.assertEqual(truncate("abcdef", 4), "abc…")
        self.assertEqual(truncate("中文中文", 5), "中文…")
        self.assertEqual(truncate("ab", 4), "ab")

    def test_action_summaries(self):
        cases = [
            ({"type": "swipe", "from": {"x": 1, "y": 2}, "to": {"x": 3, "y": 4}}, "swipe (1,2)→(3,4)"),
            ({"type": "launch", "packageName": "com.a.b"}, "launch com.a.b"),
            ({"type": "deeplink", "uri": "x://y"}, "deeplink x://y"),
            ({"type": "wait", "millis": 500}, "wait 500ms"),
            ({"type": "back"}, "back"),
            ({"type": "ask_user", "question": "继续？", "takeover": True}, 'ask_user "继续？" (takeover)'),
            ({"type": "finish", "success": False, "summary": "no"}, 'finish fail "no"'),
            ({"type": "type", "text": "a\nb", "nodeId": 3, "submit": True}, 'type "a b" → #3 +submit'),
        ]
        for action, expected in cases:
            with self.subTest(action=action):
                self.assertEqual(summarize_action(action), expected)


class LogcatTest(unittest.TestCase):
    def test_threadtime(self):
        ev = parse_line('10-31 12:00:01.234  1234  1250 I PhonePal: r1 StepPlanned '
                        '{"step": 3, "action": {"type": "tap", "nodeId": 12}}\n')
        self.assertEqual((ev.run_id, ev.kind, ev.time), ("r1", "StepPlanned", "12:00:01.234"))
        self.assertEqual(ev.data["step"], 3)
        self.assertFalse(ev.terminal)
        self.assertEqual(format_event(ev), "12:00:01.234 r1 StepPlanned  #3 tap #12")

    def test_brief_and_raw(self):
        ev = parse_line("I/PhonePal( 1234): r2 Failed {\"message\": \"boom\"}")
        self.assertEqual((ev.run_id, ev.kind), ("r2", "Failed"))
        self.assertTrue(ev.terminal)
        self.assertFalse(ev.success)
        self.assertEqual(format_event(ev), "r2 Failed  | boom")
        ev = parse_line('r3 PhoneTaskFinished {"success": true, "summary": "完成", "steps": 4}')
        self.assertTrue(ev.terminal)
        self.assertTrue(ev.success)
        self.assertIn("ok=yes | 完成", format_event(ev))

    def test_agent_event_style_fields(self):
        ev = parse_line('I PhonePal: r1 StepExecuted {"stepIndex": 2, "action": {"type": "back"}, '
                        '"result": {"ok": false, "message": "no window"}}')
        self.assertEqual(format_event(ev), "r1 StepExecuted  #2 back ok=no | no window")

    def test_truncated_json_and_noise(self):
        ev = parse_line('I PhonePal: r1 Reply {"text": "cut')
        self.assertIsNone(ev.data)
        self.assertEqual(format_event(ev), 'r1 Reply  {"text": "cut')
        self.assertIsNone(parse_line("--------- beginning of main"))
        self.assertIsNone(parse_line("I PhonePal: skills reloaded, 0 problems"))
        self.assertIsNone(parse_line(""))


if __name__ == "__main__":
    unittest.main()
