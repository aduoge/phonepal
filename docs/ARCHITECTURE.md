# PhonePal architecture

PhonePal is an open-source personal agent that lives on an Android phone: you chat with it in its
app, and it operates the other apps on the phone for you. The framework provides the hands, the
eyes, the memory, the safety gate and the model plumbing; users add **scenario skills** (taxi,
takeout, tickets ...) as small Markdown/YAML folders, without rebuilding the APK.

Android 11 (API 30) or newer. iOS does not allow an app to operate other apps; see the README.

## Modules

| Module | Kind | Owns |
|---|---|---|
| `core-api` | Kotlin/JVM | The contracts below. Every other module depends on it and only on it. |
| `core-llm` | Kotlin/JVM | `OpenAiCompatibleClient`: chat completions, images, tool calls, JSON mode, retries |
| `core-memory` | Kotlin/JVM | `FileMemoryStore`: Basic Memory Markdown notes on disk plus a CJK-aware search index |
| `core-skills` | Kotlin/JVM | `DirectorySkillRepository`: loads and validates `SKILL.md` and `flow.yaml` |
| `core-sentinel` | Kotlin/JVM | `PolicySentinel`: allow / ask / deny, task-scoped approvals, audit |
| `core-agent` | Kotlin/JVM | `PhonePalAgent`: chat orchestration, the operator loop, flows, learning notes |
| `hands` | Android library | `PhonePalAccessibilityService`, `AccessibilityDevice`, the status overlay |
| `app` | Android app | Chat UI, settings, skills and memory screens, approvals, debug bridge, traces |
| `cli/` | Python 3 (stdlib) | Developer tool for skill authors: lint, push, run, logs, traces over adb |

The five `core-*` JVM modules never touch Android APIs, so they are unit-tested on any machine.

## One chat turn

```
user text ──► PhonePalAgent.handle
               │  system prompt = persona + skill index (name: description) + memory hits
               ▼
           chat model (role CHAT) with tools:
               memory_search / memory_read / memory_write / memory_forget / list_skills / phone_task
               │
               └─ phone_task(goal, skill?) ──► operator loop (below) ──► summary back to the chat model
```

## The operator loop

```
for step in 0 until maxSteps:
    stop requested?            -> finish(false, "stopped")
    screen = device.observe()
    screen.secure?             -> ask_user(takeover = true)
    prompt = goal + skill instructions + skill memory refs + "how-to" memory hits
           + last historyWindow steps as text + compact node list (≤ maxNodes) + screenshot
    reply  = operator model (role OPERATOR, jsonMode)
    action = parse(reply)       # tolerant: fenced JSON, extra keys, nodeId or point
    finish / ask_user           -> handled by the loop, not the device
    sentinel.authorize(ctx, action) == false -> event StepBlocked, tell the model next step
    result = device.perform(action)
    same fingerprint stuckRepeats times -> tell the model it is stuck; then fail
```

Operator reply format (one JSON object, nothing else):

```json
{"thought": "目的地输入框在顶部", "action": {"type": "tap", "nodeId": 12, "target": "目的地输入框", "reason": "先输入目的地"}}
```

`action` is an `Action` from `core-api` (`type` is the discriminator: `tap`, `long_press`, `swipe`,
`type`, `back`, `home`, `launch`, `deeplink`, `wait`, `ask_user`, `finish`). Points are normalized
0..1000. A tap names either `nodeId` (preferred when the node list has the element) or `point`.

After a successful task with `learnFromSuccess`, the agent writes or updates the note
`howto/<skill-or-app>` (type `howto`) with observations such as
`- [path] 打开高德 → 打车 → 输入目的地 → 快车 → 停在确认呼叫 #taxi-home`. Before each task the agent
searches `howto` notes for the goal and puts the hits into the operator prompt.

## Skills

A skill is a folder: `SKILL.md` (frontmatter + Markdown instructions) and optional `flow.yaml`.
The format is documented in `core-api/src/main/kotlin/dev/phonepal/api/Skills.kt` and in
[`docs/skills.md`](skills.md). Examples live in `skills/examples/`.

Skill folders on the phone: `<external files>/skills/<name>/` where `<external files>` is
`/sdcard/Android/data/dev.phonepal.app/files`. The app also ships the examples as assets.

## Memory

Notes are Markdown files under `<external files>/memory/`, in the Basic Memory format
(frontmatter `title/type/permalink/tags`, `## Observations` with `- [category] content #tag (context)`,
`## Relations` with `- relation [[Target]]`). The folder can be copied into an Obsidian vault.
Forgetting deletes the file. PhonePal implements the format itself; it does not embed Basic Memory
(AGPL-3.0) code.

## Safety (Sentinel)

Evaluated outside the model for every action:

1. `deny`: typing into a password field; actions on a secure (FLAG_SECURE) screen; packages outside
   the skill's `apps` when the skill lists any (launcher and PhonePal itself excepted).
2. `ask`: a `tap`/`long_press`/`type` whose target or tapped node text contains a confirmation word:
   the built-in list (支付, 付款, 确认支付, 立即支付, 转账, 发送, 删除, 下单, 提交订单, 确认呼叫, 呼叫, 购买,
   pay, send, delete, purchase, order) plus the skill's `confirm_before`; with a skill, a `deeplink`
   that matches none of the skill's `deeplinks` templates (same scheme and host).
3. `allow`: everything else.

Approvals are ONCE or TASK scoped. Every decision is written to the audit sink.

## Debug bridge (debug builds only)

The app registers `dev.phonepal.app.debug.DebugReceiver` (exported only in the debug manifest):

| Broadcast action | Extras | Effect |
|---|---|---|
| `dev.phonepal.debug.RUN` | `goal` (string), `skill` (optional), `run_id` (optional) | Runs `Agent.runPhoneTask` in the foreground service |
| `dev.phonepal.debug.STOP` | | Sets the stop signal of the running task |
| `dev.phonepal.debug.RELOAD_SKILLS` | | Calls `SkillRepository.reload()` and logs problems |

Example: `adb shell am broadcast -n dev.phonepal.app/.debug.DebugReceiver -a dev.phonepal.debug.RUN --es goal "打开设置" --es run_id r1`

Logs: logcat tag `PhonePal`, one line per event: `PhonePal: <run_id> <EventKind> <json>`.

Traces: `<external files>/traces/<run_id>/trace.jsonl`, one JSON object per line:

```json
{"time": 1761830000000, "run": "r1", "kind": "StepPlanned", "step": 3, "action": {"type": "tap", "nodeId": 12}, "text": null, "ok": null, "decision": null}
```

`kind` is the `AgentEvent` class name. `text` carries Reply/NeedsUser/finish summaries and failure
messages; `ok` carries `ActionResult.ok` or `success`; `decision` is `allow|ask|deny: <reason>`.
Screenshots of each step are saved beside it as `step-<n>.png`.

## Models

Any OpenAI-compatible endpoint. Two settings: the chat model and the operator (screen) model,
which must accept images. Volcano Ark example: base URL `https://ark.cn-beijing.volces.com/api/v3`,
key from the `ARK_API_KEY` environment variable on a development machine, typed into Settings on
the phone. API keys are never written to logs, traces or memory.

## Building

`scripts/gradle.sh <tasks>` keeps build output outside the checkout. JDK 17 and Android SDK 35.
