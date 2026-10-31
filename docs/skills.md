# Writing skills

A skill teaches PhonePal one scenario (taxi, takeout, a settings page ...). It is a folder with a
`SKILL.md` and an optional `flow.yaml`; no APK rebuild is needed. Examples: `skills/examples/`.

```
taxi-home/
├── SKILL.md      # frontmatter + instructions for the operator model
└── flow.yaml     # optional fixed steps
```

## Where skills live

On the phone: `/sdcard/Android/data/dev.phonepal.app/files/skills/<name>/`. The app also ships the
examples as assets; a folder on the phone with the same `name` overrides the bundled skill. Skills
are reloaded on app start and by the `dev.phonepal.debug.RELOAD_SKILLS` broadcast (debug builds).

## SKILL.md

```markdown
---
name: taxi-home
description: 用高德地图打车回家（呼叫前需用户确认）
apps: [com.autonavi.minimap]
channel: deeplink-then-gui
confirm_before: [确认呼叫]
memory: [places/家]
deeplinks:
  route: "amapuri://route/plan/?sourceApplication=phonepal&dname={destination}&dev=0&t=0"
---
# 高德打车回家

1. 目的地取自记忆 `places/家`；没有地址时用 ask_user 询问，不要猜。
2. 点「确认呼叫」前必须得到用户确认。
```

| Key | Required | Value |
|---|---|---|
| `name` | yes | `[a-z0-9-]+`, equal to the folder name, unique |
| `description` | yes | One line. The chat model picks skills by it, so say what and with which app |
| `apps` | no | Package names the skill may operate. When set, Sentinel denies every other package |
| `channel` | no | `api`, `cli`, `deeplink`, `gui` (default), `deeplink-then-gui`, `mixed` |
| `confirm_before` | no | Extra confirmation words (list or a single string) |
| `memory` | no | Note permalinks loaded into the operator prompt, e.g. `places/家` |
| `deeplinks` | no | `name: "scheme://..."`; `{placeholders}` are filled by the operator |

The body after the frontmatter is Markdown for the operator model. Keep it short and concrete:
the path through the app, what to read back to the user, when to `ask_user` (login, captcha,
sold-out items) and when to `finish`.

## flow.yaml

Fixed steps run in order; `ai` steps hand a sub-goal to the free operator loop.

```yaml
steps:
  - launch: com.autonavi.minimap               # must be in apps when apps is set
  - deeplink: route                            # a deeplinks key, or a literal scheme:// URI
  - wait: 1500                                 # milliseconds, 0..600000
  - ai: 切换到打车页，选择快车并读出预估价
  - type: { target: 目的地, text: "{destination}" }
  - confirm: 确认呼叫快车回家？                  # explicit user confirmation
  - tap: 确认呼叫                               # the element best matching this text
  - assert: 正在为您呼叫                        # the screen must show this, else the flow fails
```

Each step is exactly one `kind: value` pair.

## Validation

Skills are validated on load. A skill with any problem is not loaded (the others still are), and
each problem names the file and line. The one exception is a `name` that differs from its folder:
it is reported, and the skill loads under its `name`. Unknown keys are problems, so a typo such as
`confirm-before` cannot silently drop safety words. If a user skill fails, a bundled skill with the
same name stays active.

## Safety

- Sentinel runs outside the model and cannot be turned off or relaxed by a skill.
- `confirm_before` only adds words to the built-in list (支付, 转账, 发送, 删除, 下单, 提交订单,
  确认呼叫 ...); it cannot remove any.
- `apps` only narrows what the skill may touch. A `deeplink` action that matches none of the
  skill's `deeplinks` (same scheme and host) needs user approval.
- Password fields and secure screens are always denied. Leave payment to the user, and never put
  passwords or API keys in a skill or in `memory`.
