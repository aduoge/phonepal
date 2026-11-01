# phonepal CLI

面向技能作者的开发工具。需要 Python 3.8+，只依赖标准库。通过 `adb` 与运行 PhonePal debug 包的手机
通信（见 `docs/ARCHITECTURE.md` 中的「调试桥」）。

```
python3 cli/phonepal.py [--adb PATH] [--serial S] <command> ...
```

`--adb` 默认取 `$ADB`，否则用 `PATH` 上的 `adb`；`--serial`/`-s` 指定设备（adb 也会读取
`$ANDROID_SERIAL`）。这两个选项也可以写在子命令后面。

| 命令 | 作用 |
|---|---|
| `lint <skill-dir>...` | 按 App 的规则校验 `SKILL.md` 和 `flow.yaml`；输出 `path:line: message`，有问题时退出码为 1 |
| `push <skill-dir>...` | 先校验，再替换手机上的 `/sdcard/Android/data/dev.phonepal.app/files/skills/<name>/`，并广播 `RELOAD_SKILLS` |
| `run "<goal>" [--skill name] [--run-id id] [--follow]` | 广播 `RUN`；`--follow` 持续输出该次运行的事件，直到 `PhoneTaskFinished`/`Failed`（成功时退出码为 0） |
| `stop` | 广播 `STOP` |
| `logs [--run-id id]` | 持续读取 `logcat -s PhonePal`，格式化输出 `<run_id> <EventKind> <json>` 行 |
| `traces pull <run_id> [--out dir]` | 用 `adb pull` 把 `traces/<run_id>` 拉到 `dir`（默认 `./traces`） |
| `traces show <path-or-run_id> [--dir traces] [--full]` | 把 `trace.jsonl` 渲染成步骤表；本地没有的 run id 会先拉取 |

示例：

```
python3 cli/phonepal.py lint skills/examples/*/
python3 cli/phonepal.py push skills/examples/taxi-home
python3 cli/phonepal.py run "打车回家" --skill taxi-home --follow
python3 cli/phonepal.py traces show cli-20261031-120000-ab12
```

自动生成的 run id 形如 `cli-<日期>-<时间>-<随机串>`。使用 `--follow` 时请用新的 run id：logcat 缓冲区里
残留的同 id 旧事件会让跟踪提前结束。

测试：`cd cli && python3 -m unittest discover -s tests -v`
