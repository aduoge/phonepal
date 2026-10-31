# phonepal CLI

Developer tool for skill authors. Python 3.8+, standard library only. Talks to a phone running a
debug build of PhonePal over `adb` (see "Debug bridge" in `docs/ARCHITECTURE.md`).

```
python3 cli/phonepal.py [--adb PATH] [--serial S] <command> ...
```

`--adb` defaults to `$ADB` or `adb` on `PATH`; `--serial`/`-s` selects a device (adb also honors
`$ANDROID_SERIAL`). Both options may also follow the subcommand.

| Command | What it does |
|---|---|
| `lint <skill-dir>...` | Validates `SKILL.md` and `flow.yaml` with the app's rules; prints `path:line: message`, exits 1 on problems |
| `push <skill-dir>...` | Lints, replaces `/sdcard/Android/data/dev.phonepal.app/files/skills/<name>/`, broadcasts `RELOAD_SKILLS` |
| `run "<goal>" [--skill name] [--run-id id] [--follow]` | Broadcasts `RUN`; `--follow` streams that run's events until `PhoneTaskFinished`/`Failed` (exit 0 on success) |
| `stop` | Broadcasts `STOP` |
| `logs [--run-id id]` | Streams `logcat -s PhonePal`, pretty-printing `<run_id> <EventKind> <json>` lines |
| `traces pull <run_id> [--out dir]` | `adb pull` of `traces/<run_id>` into `dir` (default `./traces`) |
| `traces show <path-or-run_id> [--dir traces] [--full]` | Renders `trace.jsonl` as a step table; a run id missing locally is pulled first |

Examples:

```
python3 cli/phonepal.py lint skills/examples/*/
python3 cli/phonepal.py push skills/examples/taxi-home
python3 cli/phonepal.py run "打车回家" --skill taxi-home --follow
python3 cli/phonepal.py traces show cli-20261031-120000-ab12
```

Generated run ids look like `cli-<date>-<time>-<rand>`. With `--follow`, use a fresh run id: events
of an earlier run with the same id still in the logcat buffer would end the follow early.

Tests: `cd cli && python3 -m unittest discover -s tests -v`
