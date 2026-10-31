"""phonepal: developer CLI for PhonePal skill authors (lint, push, run, logs, traces)."""

from __future__ import annotations

import argparse
import datetime
import os
import random
import re
import string
import sys

from . import adb as adbmod
from .adb import Adb, AdbError
from .lint import lint_paths
from .logcat import format_event, message_of, parse_line
from .traces import load_trace, render_table, resolve_trace, trace_footer

RUN_ID_RE = re.compile(r"^[A-Za-z0-9._-]+$")


def new_run_id(now=None, rng=None) -> str:
    now = now or datetime.datetime.now()
    rng = rng or random.SystemRandom()
    suffix = "".join(rng.choice(string.ascii_lowercase + string.digits) for _ in range(4))
    return "cli-%s-%s" % (now.strftime("%Y%m%d-%H%M%S"), suffix)


def _err(msg: str):
    print("phonepal: " + msg, file=sys.stderr)


# --------------------------------------------------------------------------- commands


def _lint(dirs):
    """Print problems; returns (exit_code, infos)."""
    infos = lint_paths(dirs)
    total = 0
    for info in infos:
        for p in info.problems:
            print(str(p))
        total += len(info.problems)
    names = {}
    for info in infos:
        if info.name:
            if info.name in names:
                print("%s: skill name '%s' is also used by %s" % (info.directory, info.name, names[info.name]))
                total += 1
            names.setdefault(info.name, info.directory)
    ok = sum(1 for i in infos if i.ok)
    print("%d skill(s) checked, %d ok, %d problem(s)" % (len(infos), ok, total), file=sys.stderr)
    return (1 if total else 0), infos


def cmd_lint_entry(args, ctx) -> int:
    code, _ = _lint(args.skill_dirs)
    return code


def cmd_push(args, ctx) -> int:
    code, infos = _lint(args.skill_dirs)
    if code:
        _err("not pushing: fix the problems above first")
        return 1
    adb = ctx.adb(args)
    for info in infos:
        remote, files = adb.push_skill(info.directory, info.name)
        print("pushed %s -> %s (%s)" % (info.directory, remote, ", ".join(files)))
    adb.broadcast(adbmod.ACTION_RELOAD)
    print("skills reloaded; problems found by the app are logged under tag %s (phonepal logs)" % adbmod.LOG_TAG)
    return 0


def cmd_run(args, ctx) -> int:
    run_id = args.run_id or new_run_id()
    if not RUN_ID_RE.match(run_id):
        _err("run id may only contain letters, digits, '.', '_' and '-'")
        return 2
    if not args.goal.strip():
        _err("goal must not be empty")
        return 2
    adb = ctx.adb(args)
    extras = [("goal", args.goal), ("skill", args.skill), ("run_id", run_id)]
    if not args.follow:
        adb.broadcast(adbmod.ACTION_RUN, extras)
        print("started run %s" % run_id)
        print("follow with: phonepal logs --run-id %s   trace: phonepal traces show %s" % (run_id, run_id))
        return 0
    # start logcat before the broadcast so no event is missed
    proc = adb.spawn(*adb.logcat_argv())
    try:
        adb.broadcast(adbmod.ACTION_RUN, extras)
        print("started run %s" % run_id, file=sys.stderr)
        return _follow(proc, run_id, stop_on_terminal=True)
    finally:
        _close(proc)


def _follow(proc, run_id, stop_on_terminal: bool) -> int:
    """Print events (optionally for one run). Returns the exit code."""
    try:
        for line in proc.stdout:
            ev = parse_line(line)
            if ev is None:
                if run_id is None:
                    msg = message_of(line)
                    if msg is not None:
                        print(msg.rstrip())
                continue
            if run_id is not None and ev.run_id != run_id:
                continue
            print(format_event(ev), flush=True)
            if stop_on_terminal and ev.terminal:
                return 0 if ev.success else 1
    except KeyboardInterrupt:
        return 130
    if stop_on_terminal:
        _err("logcat ended before the run finished")
        return 1
    return 0


def _close(proc):
    try:
        if getattr(proc, "poll", lambda: None)() is None:
            proc.terminate()
        proc.wait()
    except Exception:  # best effort; the process may already be gone
        pass


def cmd_stop(args, ctx) -> int:
    ctx.adb(args).broadcast(adbmod.ACTION_STOP)
    print("stop requested")
    return 0


def cmd_logs(args, ctx) -> int:
    if args.run_id and not RUN_ID_RE.match(args.run_id):
        _err("invalid run id")
        return 2
    adb = ctx.adb(args)
    proc = adb.spawn(*adb.logcat_argv())
    try:
        return _follow(proc, args.run_id, stop_on_terminal=False)
    finally:
        _close(proc)


def cmd_traces_pull(args, ctx) -> int:
    if not RUN_ID_RE.match(args.run_id):
        _err("invalid run id")
        return 2
    local = ctx.adb(args).pull_trace(args.run_id, args.out)
    print("pulled trace to %s" % local)
    return 0


def cmd_traces_show(args, ctx) -> int:
    path = resolve_trace(args.target, args.dir)
    if path is None:
        if os.path.exists(args.target) or not RUN_ID_RE.match(args.target):
            _err("no trace.jsonl at %s" % args.target)
            return 1
        print("trace %s not found under %s/, pulling it from the device" % (args.target, args.dir), file=sys.stderr)
        ctx.adb(args).pull_trace(args.target, args.dir)
        path = resolve_trace(args.target, args.dir)
        if path is None:
            _err("the pulled trace has no trace.jsonl")
            return 1
    events = load_trace(path)
    print(render_table(events, full=args.full))
    print(trace_footer(events, os.path.dirname(path)))
    return 0


# --------------------------------------------------------------------------- parser


class Context:
    """Creates the Adb instance; tests replace ``adb_factory``."""

    def __init__(self, adb_factory=None):
        self.adb_factory = adb_factory

    def adb(self, args) -> Adb:
        if self.adb_factory:
            return self.adb_factory(args)
        return Adb(adb=args.adb, serial=args.serial, echo=lambda s: print(s, file=sys.stderr))


def build_parser() -> argparse.ArgumentParser:
    # global options are accepted before or after the subcommand
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--adb", default=argparse.SUPPRESS,
                        help="adb executable (default: $ADB or 'adb')")
    common.add_argument("--serial", "-s", default=argparse.SUPPRESS,
                        help="device serial (default: adb's choice / $ANDROID_SERIAL)")

    parser = argparse.ArgumentParser(prog="phonepal", description=__doc__)
    parser.add_argument("--adb", default=None, help="adb executable (default: $ADB or 'adb')")
    parser.add_argument("--serial", "-s", default=None, help="device serial")
    sub = parser.add_subparsers(dest="command", metavar="COMMAND")
    sub.required = True

    p = sub.add_parser("lint", parents=[common], help="validate skill folders like the app does")
    p.add_argument("skill_dirs", nargs="+", metavar="skill-dir")
    p.set_defaults(func=cmd_lint_entry)

    p = sub.add_parser("push", parents=[common], help="lint, push skill folders to the phone and reload")
    p.add_argument("skill_dirs", nargs="+", metavar="skill-dir")
    p.set_defaults(func=cmd_push)

    p = sub.add_parser("run", parents=[common], help="start a phone task through the debug bridge")
    p.add_argument("goal")
    p.add_argument("--skill", help="skill name to use")
    p.add_argument("--run-id", help="run id (default: generated); use a fresh one with --follow")
    p.add_argument("--follow", "-f", action="store_true",
                   help="stream this run's events until PhoneTaskFinished/Failed; exit 0 on success")
    p.set_defaults(func=cmd_run)

    p = sub.add_parser("stop", parents=[common], help="stop the running task")
    p.set_defaults(func=cmd_stop)

    p = sub.add_parser("logs", parents=[common], help="stream PhonePal events from logcat")
    p.add_argument("--run-id", help="only events of this run")
    p.set_defaults(func=cmd_logs)

    p = sub.add_parser("traces", help="pull and show run traces")
    tsub = p.add_subparsers(dest="traces_command", metavar="ACTION")
    tsub.required = True
    t = tsub.add_parser("pull", parents=[common], help="adb pull traces/<run_id>")
    t.add_argument("run_id")
    t.add_argument("--out", default="traces", help="local folder (default: ./traces)")
    t.set_defaults(func=cmd_traces_pull)
    t = tsub.add_parser("show", parents=[common], help="render a trace.jsonl as a step table")
    t.add_argument("target", metavar="path-or-run_id",
                   help="trace.jsonl, its folder, or a run id (looked up in --dir, pulled if missing)")
    t.add_argument("--dir", default="traces", help="local traces folder for run ids (default: ./traces)")
    t.add_argument("--full", action="store_true", help="do not truncate long cells")
    t.set_defaults(func=cmd_traces_show)
    return parser


def main(argv=None, adb_factory=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="backslashreplace")
        except (AttributeError, ValueError):
            pass
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args, Context(adb_factory))
    except AdbError as e:
        _err(str(e))
        return 1
    except KeyboardInterrupt:
        return 130
