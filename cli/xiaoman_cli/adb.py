"""adb invocation: argument-list subprocess calls, never a host shell.

Remote commands for ``adb shell`` are passed as ONE argument, built with
``shlex.quote`` so the device's sh sees each value (Chinese, spaces, quotes) intact.
"""

from __future__ import annotations

import os
import shlex
import subprocess

APP_PACKAGE = "dev.xiaoman.app"
EXTERNAL_FILES = "/sdcard/Android/data/%s/files" % APP_PACKAGE
REMOTE_SKILLS = EXTERNAL_FILES + "/skills"
REMOTE_TRACES = EXTERNAL_FILES + "/traces"
RECEIVER = APP_PACKAGE + "/.debug.DebugReceiver"
ACTION_RUN = "dev.xiaoman.debug.RUN"
ACTION_STOP = "dev.xiaoman.debug.STOP"
ACTION_RELOAD = "dev.xiaoman.debug.RELOAD_SKILLS"
LOG_TAG = "Xiaoman"


class AdbError(Exception):
    pass


def default_adb_path() -> str:
    return os.environ.get("ADB") or "adb"


def shell_join(parts) -> str:
    """Quote each part for the device's POSIX sh and join them."""
    return " ".join(shlex.quote(str(p)) for p in parts)


def broadcast_command(action: str, extras=()) -> str:
    """Remote command string for ``am broadcast`` to the debug receiver.

    ``extras`` is a sequence of (key, value) string extras; None values are skipped.
    """
    parts = ["am", "broadcast", "-n", RECEIVER, "-a", action]
    for key, value in extras:
        if value is None:
            continue
        parts += ["--es", key, value]
    return shell_join(parts)


def _default_runner(argv):
    return subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          universal_newlines=True, encoding="utf-8", errors="replace")


def _default_spawner(argv):
    return subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            universal_newlines=True, encoding="utf-8", errors="replace", bufsize=1)


class Adb:
    """Thin adb wrapper. ``runner(argv)`` returns an object with ``returncode`` and
    ``stdout``; ``spawner(argv)`` returns a Popen-like object with a line-iterable
    ``stdout``, ``terminate()`` and ``wait()``. Both are injectable for tests."""

    def __init__(self, adb: str = None, serial: str = None, runner=None, spawner=None, echo=None):
        self.adb = adb or default_adb_path()
        self.serial = serial
        self.runner = runner or _default_runner
        self.spawner = spawner or _default_spawner
        self.echo = echo  # optional callable(str) for adb output

    def argv(self, *args):
        base = [self.adb]
        if self.serial:
            base += ["-s", self.serial]
        return base + [str(a) for a in args]

    def run(self, *args, check: bool = True):
        argv = self.argv(*args)
        try:
            result = self.runner(argv)
        except FileNotFoundError:
            raise AdbError("adb not found at %r; pass --adb PATH or set ADB" % self.adb)
        out = getattr(result, "stdout", "") or ""
        if self.echo and out.strip():
            self.echo(out.rstrip("\n"))
        if check and result.returncode != 0:
            raise AdbError("%s failed with exit code %d%s" % (
                " ".join(shlex.quote(a) for a in argv), result.returncode,
                (": " + out.strip().splitlines()[-1]) if out.strip() else ""))
        return result

    def shell(self, remote_command: str, check: bool = True):
        return self.run("shell", remote_command, check=check)

    def spawn(self, *args):
        argv = self.argv(*args)
        try:
            return self.spawner(argv)
        except FileNotFoundError:
            raise AdbError("adb not found at %r; pass --adb PATH or set ADB" % self.adb)

    # ------------------------------------------------------------------ high level

    def broadcast(self, action: str, extras=()):
        result = self.shell(broadcast_command(action, extras))
        out = getattr(result, "stdout", "") or ""
        # `am broadcast` exits 0 even when nothing received it
        if "Error" in out or "Exception" in out:
            raise AdbError("broadcast %s failed: %s" % (action, out.strip()))
        return result

    def push_skill(self, local_dir: str, name: str):
        """Replace /sdcard/.../skills/<name>/ with the files of ``local_dir``."""
        remote = REMOTE_SKILLS + "/" + name
        self.shell(shell_join(["rm", "-rf", remote]) + " && " + shell_join(["mkdir", "-p", remote]))
        pushed = []
        for rel in skill_files(local_dir):
            remote_path = remote + "/" + rel.replace(os.sep, "/")
            parent = remote_path.rsplit("/", 1)[0]
            if parent != remote:
                self.shell(shell_join(["mkdir", "-p", parent]))
            self.run("push", os.path.join(local_dir, rel), remote_path)
            pushed.append(rel)
        return remote, pushed

    def logcat_argv(self):
        return ("logcat", "-v", "threadtime", "-s", LOG_TAG)

    def pull_trace(self, run_id: str, out_dir: str) -> str:
        os.makedirs(out_dir, exist_ok=True)
        self.run("pull", REMOTE_TRACES + "/" + run_id, out_dir)
        return os.path.join(out_dir, run_id)


def skill_files(local_dir: str):
    """Relative paths of files to push, skipping hidden files and folders."""
    result = []
    for root, dirs, files in os.walk(local_dir):
        dirs[:] = sorted(d for d in dirs if not d.startswith("."))
        for f in sorted(files):
            if f.startswith(".") or f.endswith("~"):
                continue
            result.append(os.path.relpath(os.path.join(root, f), local_dir))
    return result
