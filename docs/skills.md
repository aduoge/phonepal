# 编写技能

一个技能教会小满一个场景（打车、外卖、某个设置页……）。技能是一个文件夹，包含 `SKILL.md`
和可选的 `flow.yaml`，不需要重新打包 APK。示例见 `skills/examples/`。

```
taxi-home/
├── SKILL.md      # frontmatter + 给操作模型的说明
└── flow.yaml     # 可选，固定步骤
```

## 技能放在哪里

手机上：`/sdcard/Android/data/dev.xiaoman.app/files/skills/<name>/`。App 也会把示例技能作为 assets
打包；手机目录里同 `name` 的技能会覆盖内置技能。App 启动时会重新加载技能，debug 包也可以通过
`dev.xiaoman.debug.RELOAD_SKILLS` 广播触发重新加载。

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
  route: "amapuri://route/plan/?sourceApplication=xiaoman&dname={destination}&dev=0&t=0"
---
# 高德打车回家

1. 目的地取自记忆 `places/家`；没有地址时用 ask_user 询问，不要猜。
2. 点「确认呼叫」前必须得到用户确认。
```

| 字段 | 必填 | 取值 |
|---|---|---|
| `name` | 是 | `[a-z0-9-]+`，与文件夹同名且唯一 |
| `description` | 是 | 一行。聊天模型靠它挑选技能，写清楚做什么、用哪个 App |
| `apps` | 否 | 技能可以操作的包名。设置后，Sentinel 会拒绝操作其他包 |
| `channel` | 否 | `api`、`cli`、`deeplink`、`gui`（默认）、`deeplink-then-gui`、`mixed` |
| `confirm_before` | 否 | 追加的确认词（列表或单个字符串） |
| `memory` | 否 | 加载进操作提示词的笔记 permalink，例如 `places/家` |
| `deeplinks` | 否 | `名称: "scheme://..."`；`{占位符}` 由操作模型填写 |

frontmatter 之后的正文是写给操作模型的 Markdown。尽量简短具体：在 App 里怎么走、要给用户读出什么、
什么时候 `ask_user`（登录、验证码、商品售罄）、什么时候 `finish`。

## flow.yaml

固定步骤按顺序执行；`ai` 步骤把一个子目标交给自由操作循环。

```yaml
steps:
  - launch: com.autonavi.minimap               # 设置了 apps 时，必须在 apps 里
  - deeplink: route                            # deeplinks 中的键名，或字面的 scheme:// URI
  - wait: 1500                                 # 毫秒，0..600000
  - ai: 切换到打车页，选择快车并读出预估价
  - type: { target: 目的地, text: "{destination}" }
  - confirm: 确认呼叫快车回家？                  # 明确请用户确认
  - tap: 确认呼叫                               # 点击与该文字最匹配的元素
  - assert: 正在为您呼叫                        # 屏幕上必须出现这段内容，否则流程失败
```

每个步骤恰好是一个 `类型: 值` 键值对。

## 校验

技能在加载时校验。有任何问题的技能都不会被加载（其他技能照常加载），每个问题都会标出文件和行号。
唯一的例外是 `name` 与文件夹名不一致：会报告问题，但技能仍以 `name` 加载。未知字段也算问题，
这样 `confirm-before` 之类的拼写错误不会悄悄丢掉安全确认词。用户技能加载失败时，同名的内置技能保持生效。

在电脑上可以先用 CLI 校验：`python3 cli/xiaoman.py lint <技能目录>`。

## 安全

- Sentinel 在模型之外运行，技能无法关闭或放宽它。
- `confirm_before` 只能在内置列表（支付、转账、发送、删除、下单、提交订单、确认呼叫……）上追加，不能删除。
- `apps` 只会缩小技能可以操作的范围。`deeplink` 操作如果与技能的 `deeplinks` 都不匹配（scheme 和 host
  都相同才算匹配），需要用户批准。
- 密码框和受保护页面一律拒绝操作。付款请留给用户完成；不要把密码或 API Key 写进技能或 `memory`。
