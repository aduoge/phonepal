import contextlib
import io
import os
import shlex
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

from phonepal_cli.adb import Adb, broadcast_command  # noqa: E402
from phonepal_cli.main import build_parser, main, new_run_id  # noqa: E402

FIXTURE = os.path.join(HERE, "fixtures", "skills", "ride-home")
SKILLS = "/sdcard/Android/data/dev.phonepal.app/files/skills"
RECEIVER = "dev.phonepal.app/.debug.DebugReceiver"


class Result:
    def __init__(self, returncode=0, stdout=""):
        self.returncode = returncode
        self.stdout = stdout


class FakeProc:
    def __init__(self, lines):
        self.stdout = iter(lines)
        self.terminated = False
        self.waited = False

    def poll(self):
        return None

    def terminate(self):
        self.terminated = True

    def wait(self):
        self.waited = True


class FakeAdb:
    """Records every adb argv; spawned processes yield ``lines``."""

    def __init__(self, lines=(), results=None):
        self.calls = []
        self.lines = list(lines)
        self.results = results or {}
        self.procs = []

    def runner(self, argv):
        self.calls.append(("run", argv))
        for key, result in self.results.items():
            if key in " ".join(argv):
                return result
        return Result(0, "Broadcast completed: result=0\n")

    def spawner(self, argv):
        self.calls.append(("spawn", argv))
        proc = FakeProc(self.lines)
        self.procs.append(proc)
        return proc

    def factory(self, args):
        return Adb(adb=args.adb or "adb", serial=args.serial, runner=self.runner, spawner=self.spawner)

    def run_main(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = main(argv, adb_factory=self.factory)
        return code, out.getvalue(), err.getvalue()


def remote_words(argv):
    """How the device shell will split the single remote command argument."""
    assert argv[-2] == "shell", argv
    return shlex.split(argv[-1])


class BroadcastTest(unittest.TestCase):
    def test_quoting_roundtrip(self):
        goal = "打开 设置 里的 \"WLAN\"; rm -rf / $(x) 'q'"
        cmd = broadcast_command("dev.phonepal.debug.RUN", [("goal", goal), ("skill", None), ("run_id", "r1")])
        self.assertEqual(shlex.split(cmd), ["am", "broadcast", "-n", RECEIVER, "-a", "dev.phonepal.debug.RUN",
                                            "--es", "goal", goal, "--es", "run_id", "r1"])

    def test_run_id(self):
        rid = new_run_id()
        self.assertRegex(rid, r"^cli-\d{8}-\d{6}-[a-z0-9]{4}$")


class CommandTest(unittest.TestCase):
    def test_run(self):
        fake = FakeAdb()
        code, out, _ = fake.run_main(["--serial", "emu1", "run", "打开 设置", "--skill", "open-settings-wifi",
                                      "--run-id", "r1"])
        self.assertEqual(code, 0)
        (kind, argv), = fake.calls
        self.assertEqual(argv[:4], ["adb", "-s", "emu1", "shell"])
        self.assertEqual(len(argv), 5)
        self.assertEqual(remote_words(argv), [
            "am", "broadcast", "-n", RECEIVER, "-a", "dev.phonepal.debug.RUN",
            "--es", "goal", "打开 设置", "--es", "skill", "open-settings-wifi", "--es", "run_id", "r1"])
        self.assertIn("started run r1", out)

    def test_run_generates_id_and_options_after_subcommand(self):
        fake = FakeAdb()
        code, out, _ = fake.run_main(["run", "go", "--adb", "/opt/adb", "-s", "X"])
        self.assertEqual(code, 0)
        argv = fake.calls[0][1]
        self.assertEqual(argv[:3], ["/opt/adb", "-s", "X"])
        words = remote_words(argv)
        self.assertNotIn("skill", words)
        self.assertRegex(words[words.index("run_id") + 1], r"^cli-")

    def test_adb_env_default(self):
        old = os.environ.get("ADB")
        os.environ["ADB"] = "/env/adb"
        try:
            args = build_parser().parse_args(["stop"])
            self.assertEqual(Adb(adb=args.adb).argv("devices"), ["/env/adb", "devices"])
        finally:
            if old is None:
                del os.environ["ADB"]
            else:
                os.environ["ADB"] = old

    def test_run_rejects_bad_run_id(self):
        fake = FakeAdb()
        code, _, err = fake.run_main(["run", "go", "--run-id", "a b/c"])
        self.assertEqual(code, 2)
        self.assertEqual(fake.calls, [])

    def test_run_follow(self):
        lines = [
            "--------- beginning of main\n",
            "10-31 12:00:00.000  1  1 I PhonePal: old StepPlanned {\"step\": 9}\n",
            "10-31 12:00:01.000  1  1 I PhonePal: r9 PhoneTaskStarted {\"goal\": \"打开设置\"}\n",
            "10-31 12:00:02.000  1  1 I PhonePal: r9 StepPlanned {\"step\": 0, \"action\": {\"type\": \"home\"}}\n",
            "10-31 12:00:03.000  1  1 I PhonePal: r9 PhoneTaskFinished {\"success\": true, \"summary\": \"ok\"}\n",
            "10-31 12:00:04.000  1  1 I PhonePal: r9 StepPlanned {\"step\": 99}\n",
        ]
        fake = FakeAdb(lines)
        code, out, _ = fake.run_main(["run", "打开设置", "--run-id", "r9", "--follow"])
        self.assertEqual(code, 0)
        # logcat is spawned before the broadcast
        self.assertEqual(fake.calls[0], ("spawn", ["adb", "logcat", "-v", "threadtime", "-s", "PhonePal"]))
        self.assertEqual(fake.calls[1][0], "run")
        shown = out.strip().splitlines()
        self.assertEqual(len(shown), 3)
        self.assertNotIn("old", out)
        self.assertNotIn("#99", out)
        self.assertTrue(fake.procs[0].terminated)

    def test_run_follow_failure_exit_code(self):
        fake = FakeAdb(["I PhonePal: r9 Failed {\"message\": \"x\"}\n"])
        code, _, _ = fake.run_main(["run", "g", "--run-id", "r9", "-f"])
        self.assertEqual(code, 1)
        fake = FakeAdb([])
        code, _, err = fake.run_main(["run", "g", "--run-id", "r9", "-f"])
        self.assertEqual(code, 1)
        self.assertIn("logcat ended", err)

    def test_stop(self):
        fake = FakeAdb()
        self.assertEqual(fake.run_main(["stop"])[0], 0)
        self.assertEqual(remote_words(fake.calls[0][1]),
                         ["am", "broadcast", "-n", RECEIVER, "-a", "dev.phonepal.debug.STOP"])

    def test_broadcast_error_output(self):
        fake = FakeAdb(results={"STOP": Result(0, "Error: Bad component name")})
        code, _, err = fake.run_main(["stop"])
        self.assertEqual(code, 1)
        self.assertIn("Bad component name", err)

    def test_adb_failure(self):
        fake = FakeAdb(results={"STOP": Result(1, "error: no devices/emulators found")})
        code, _, err = fake.run_main(["stop"])
        self.assertEqual(code, 1)
        self.assertIn("no devices", err)

    def test_adb_missing(self):
        def missing(argv):
            raise FileNotFoundError(argv[0])
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = main(["stop"], adb_factory=lambda a: Adb(adb="nope", runner=missing))
        self.assertEqual(code, 1)
        self.assertIn("--adb", err.getvalue())

    def test_logs(self):
        lines = [
            "I PhonePal: r1 StepPlanned {\"step\": 1}\n",
            "I PhonePal: r2 StepPlanned {\"step\": 2}\n",
            "I PhonePal: skills reloaded: 1 problem\n",
        ]
        code, out, _ = FakeAdb(lines).run_main(["logs"])
        self.assertEqual(code, 0)
        self.assertEqual(out.splitlines(), ["r1 StepPlanned  #1", "r2 StepPlanned  #2",
                                            "skills reloaded: 1 problem"])
        code, out, _ = FakeAdb(lines).run_main(["logs", "--run-id", "r2"])
        self.assertEqual(out.splitlines(), ["r2 StepPlanned  #2"])


class PushTest(unittest.TestCase):
    def test_push(self):
        fake = FakeAdb()
        code, out, _ = fake.run_main(["push", FIXTURE, "--serial", "S"])
        self.assertEqual(code, 0, out)
        runs = [argv for kind, argv in fake.calls]
        self.assertEqual(remote_words(runs[0]), ["rm", "-rf", SKILLS + "/ride-home", "&&",
                                                 "mkdir", "-p", SKILLS + "/ride-home"])
        self.assertEqual(runs[1], ["adb", "-s", "S", "push", os.path.join(FIXTURE, "SKILL.md"),
                                   SKILLS + "/ride-home/SKILL.md"])
        self.assertEqual(runs[2], ["adb", "-s", "S", "push", os.path.join(FIXTURE, "flow.yaml"),
                                   SKILLS + "/ride-home/flow.yaml"])
        self.assertEqual(remote_words(runs[3])[-1], "dev.phonepal.debug.RELOAD_SKILLS")
        self.assertEqual(len(runs), 4)

    def test_push_refuses_invalid(self):
        tmp = tempfile.mkdtemp()
        try:
            d = os.path.join(tmp, "bad")
            os.makedirs(d)
            with open(os.path.join(d, "SKILL.md"), "w", encoding="utf-8") as f:
                f.write("---\nname: bad\n---\n")
            fake = FakeAdb()
            code, out, _ = fake.run_main(["push", d])
            self.assertEqual(code, 1)
            self.assertIn("SKILL.md:1: missing required key 'description'", out)
            self.assertEqual(fake.calls, [])
        finally:
            shutil.rmtree(tmp)

    def test_lint_command(self):
        code, out, err = FakeAdb().run_main(["lint", FIXTURE])
        self.assertEqual((code, out), (0, ""))
        self.assertIn("1 skill(s) checked, 1 ok", err)


class TracesCommandTest(unittest.TestCase):
    def test_pull(self):
        tmp = tempfile.mkdtemp()
        try:
            fake = FakeAdb()
            out_dir = os.path.join(tmp, "out")
            code, out, _ = fake.run_main(["traces", "pull", "r1", "--out", out_dir])
            self.assertEqual(code, 0)
            self.assertEqual(fake.calls[0][1], ["adb", "pull",
                                                "/sdcard/Android/data/dev.phonepal.app/files/traces/r1", out_dir])
            self.assertTrue(os.path.isdir(out_dir))
            self.assertIn(os.path.join(out_dir, "r1"), out)
        finally:
            shutil.rmtree(tmp)

    def test_show_local(self):
        fake = FakeAdb()
        code, out, _ = fake.run_main(["traces", "show", "r1", "--dir", os.path.join(HERE, "fixtures", "traces")])
        self.assertEqual(code, 0)
        self.assertIn("StepBlocked", out)
        self.assertIn("finished: success", out)
        self.assertEqual(fake.calls, [])

    def test_show_pulls_missing_run(self):
        tmp = tempfile.mkdtemp()
        try:
            fake = FakeAdb()

            def runner(argv):
                fake.calls.append(("run", argv))
                d = os.path.join(argv[-1], "r7")
                os.makedirs(d)
                with open(os.path.join(d, "trace.jsonl"), "w") as f:
                    f.write('{"time": 0, "kind": "Failed", "text": "boom"}\n')
                return Result()
            fake.runner = runner
            code, out, _ = fake.run_main(["traces", "show", "r7", "--dir", tmp])
            self.assertEqual(code, 0)
            self.assertIn("boom", out)
            self.assertEqual(fake.calls[0][1][1], "pull")
        finally:
            shutil.rmtree(tmp)


if __name__ == "__main__":
    unittest.main()
