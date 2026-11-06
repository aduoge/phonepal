# 小满架构

小满是一个跑在 Android 手机上的开源私人助手：你在它的 App 里聊天，它替你操作手机上的其他
App。框架负责“手”（无障碍操作）、“眼”（读屏）、记忆、安全闸门和模型调用；具体场景（打车、外卖、
购票……）由用户以**场景技能**的形式添加。技能是 Markdown/YAML 小文件夹，不需要重新打包 APK。

需要 Android 11（API 30）及以上。iOS 不允许一个 App 操作其他 App，只能由 Mac 通过 WebDriverAgent 操作 iPhone（`ios-hands` + `ios-runner`），见 [ios.md](ios.md)。

## 模块

| 模块 | 类型 | 职责 |
|---|---|---|
| `core-api` | Kotlin/JVM | 下文所有接口与数据结构。其他模块都依赖它，且只依赖它 |
| `core-llm` | Kotlin/JVM | `OpenAiCompatibleClient`：对话补全、图片输入、工具调用、JSON 模式、重试 |
| `core-memory` | Kotlin/JVM | `FileMemoryStore`：磁盘上的 Basic Memory Markdown 笔记，加上支持中文的检索索引 |
| `core-skills` | Kotlin/JVM | `DirectorySkillRepository`：加载并校验 `SKILL.md` 和 `flow.yaml` |
| `core-sentinel` | Kotlin/JVM | `PolicySentinel`：允许 / 询问 / 拒绝，任务级授权，审计 |
| `core-agent` | Kotlin/JVM | `XiaomanAgent`：聊天编排、操作循环、技能流程、经验笔记 |
| `hands` | Android 库 | `XiaomanAccessibilityService`、`AccessibilityDevice`、状态悬浮窗 |
| `app` | Android 应用 | 聊天界面、设置、技能与记忆页面、授权确认、调试桥、轨迹 |
| `core-mcp` | Kotlin/JVM | `McpToolProvider`：把 MCP 服务器（高德、滴滴等）的工具交给聊天模型，带确认策略与密钥脱敏，见 [mcp.md](mcp.md) |
| `web-server` | Kotlin/JVM 应用 | 网页版：同源移动端网页 + `/api/*`（SSE 聊天、确认、MCP 目录与测试），每个会话一个不操作手机的 `XiaomanAgent`，见 [web.md](web.md) |
| `ios-hands` | Kotlin/JVM | `WdaDevice`：通过 iPhone 上的 WebDriverAgent 实现 `Device`（截图、界面树、点击、输入、返回） |
| `ios-runner` | Kotlin/JVM 命令行 | Mac 上的 `ios-runner`：把 `core-*` 与 `WdaDevice` 组装起来，终端确认、轨迹、提醒 |
| `cli/` | Python 3（仅标准库） | 面向技能作者的开发工具：通过 adb 做校验、推送、运行、日志、轨迹 |

五个 `core-*` JVM 模块不接触任何 Android API，可以在任意机器上跑单元测试。依赖方向：

```
app ──► core-agent / core-llm / core-memory / core-skills / core-sentinel ──► core-api
 └────► hands ─────────────────────────────────────────────────────────────► core-api
```

`app/AppGraph.kt` 是手写的依赖容器，把各模块的实现组装成 `XiaomanAgent`。

### core-agent 内部文件

| 文件 | 内容 |
|---|---|
| `XiaomanAgent.kt` | `Agent` 实现：一轮聊天（`handle`）、一次手机任务（`runPhoneTask`）、成功后的经验沉淀 |
| `AgentTools.kt` | 提供给聊天模型的工具定义（名称与 JSON Schema） |
| `ChatTools.kt` | 执行聊天模型发起的工具调用 |
| `TaskRun.kt` | 一次手机任务的运行状态，以及操作循环与流程共用的结果类型 |
| `OperatorLoop.kt` | 操作循环：观察 → 请求操作模型 → 解析 → Sentinel → 执行 → 卡死检测 |
| `ScreenText.kt` | 屏幕和操作的文字表示，用于提示词、历史记录和经验笔记 |
| `FlowRunner.kt` | 按顺序执行技能的 `flow.yaml` 步骤 |
| `OperatorReplyParser.kt` | 宽松解析操作模型的回复 |
| `Prompts.kt` | 所有面向模型的中文文本，集中管理便于调优 |
| `ReminderTimes.kt` | `reminder_set` 工具的本地 ISO-8601 时间解析与当前时间格式化 |
| `JsonText.kt`、`NoteText.kt` | JSON 读取与笔记渲染的小工具 |

## 一轮聊天

```
用户输入 ──► XiaomanAgent.handle
               │  系统提示词 = 人设 + 技能索引（name: description）+ 记忆检索命中
               ▼
           聊天模型（角色 CHAT），可用工具：
               memory_search / memory_read / memory_write / memory_forget / list_skills / reminder_set / phone_task
               │
               └─ phone_task(goal, skill?) ──► 操作循环（见下）──► 结果摘要返回给聊天模型
```

## 操作循环

```
for step in 0 until maxSteps:
    用户要求停止？                -> finish(false, "stopped")
    screen = device.observe()
    screen.secure？               -> ask_user(takeover = true)
    prompt = 目标 + 技能说明 + 技能引用的记忆 + "howto" 记忆命中
           + 最近 historyWindow 步的文字记录 + 精简节点列表（≤ maxNodes）+ 截图
    reply  = 操作模型（角色 OPERATOR，jsonMode）
    action = parse(reply)        # 宽松解析：代码块包裹的 JSON、多余字段、nodeId 或坐标
    finish / ask_user            -> 由循环处理，不经过设备
    sentinel.authorize(ctx, action) == false -> 发出 StepBlocked 事件，下一步告知模型
    result = device.perform(action)
    连续 stuckRepeats 次屏幕不变  -> 提示模型已卡住；再次卡住则失败
```

操作模型的回复格式（只有一个 JSON 对象）：

```json
{"thought": "目的地输入框在顶部", "action": {"type": "tap", "nodeId": 12, "target": "目的地输入框", "reason": "先输入目的地"}}
```

`action` 是 `core-api` 中的 `Action`，以 `type` 区分：`tap`、`long_press`、`swipe`、`type`、`back`、
`home`、`launch`、`deeplink`、`wait`、`ask_user`、`finish`。坐标归一化到 0..1000。点击可以指定
`nodeId`（节点列表中有该元素时优先使用）或 `point`。

开启 `learnFromSuccess` 时，任务成功后 Agent 会写入或更新笔记 `howto/<技能名或包名>`（类型 `howto`），
内容如 `- [path] 打开高德 → 打车 → 输入目的地 → 快车 → 停在确认呼叫 #taxi-home`。每次任务开始前，
Agent 会按目标检索 `howto` 笔记，把命中结果放进操作提示词。

## 技能

技能是一个文件夹：`SKILL.md`（frontmatter + Markdown 说明）和可选的 `flow.yaml`。格式定义见
`core-api/src/main/kotlin/dev/xiaoman/api/Skills.kt` 和 [`docs/skills.md`](skills.md)，示例在
`skills/examples/`。

手机上的技能目录：`<external files>/skills/<name>/`，其中 `<external files>` 为
`/sdcard/Android/data/dev.xiaoman.app/files`。App 也会把示例技能作为 assets 一起打包。

## 记忆

笔记是 `<external files>/memory/` 下的 Markdown 文件，采用 Basic Memory 格式：frontmatter 包含
`title/type/permalink/tags`；`## Observations` 下每行为 `- [category] content #tag (context)`；
`## Relations` 下每行为 `- relation [[Target]]`。整个目录可以直接拷进 Obsidian 仓库。「忘记」会删除文件。
小满自行实现这一格式，没有内嵌 Basic Memory（AGPL-3.0）的代码。

## 安全（Sentinel）

每个操作都在模型之外评估：

1. `deny`（拒绝）：向密码框输入；在受保护（FLAG_SECURE）页面上操作；技能声明了 `apps` 时操作清单之外的
   包（桌面启动器和小满自身除外）。
2. `ask`（询问）：`tap` / `long_press` / `type` 的目标文字或被操作节点的文字包含确认词。确认词包括内置列表
   （支付、付款、确认支付、立即支付、转账、发送、删除、下单、提交订单、确认呼叫、呼叫、购买、
   pay、send、delete、purchase、order）以及技能的 `confirm_before`。在技能内，`deeplink` 与技能的
   `deeplinks` 模板都不匹配（scheme 和 host 都相同才算匹配）时也要询问。
3. `allow`（允许）：其他所有操作。

授权分为“仅一次”（ONCE）和“本次任务”（TASK）两种范围。每一次决策都写入审计记录。

## 调试桥（仅 debug 包）

App 注册了 `dev.xiaoman.app.debug.DebugReceiver`（只在 debug 清单中导出）：

| 广播 action | 参数 | 作用 |
|---|---|---|
| `dev.xiaoman.debug.RUN` | `goal`（字符串）、`skill`（可选）、`run_id`（可选） | 在前台服务中执行 `Agent.runPhoneTask` |
| `dev.xiaoman.debug.STOP` | 无 | 给正在运行的任务发送停止信号 |
| `dev.xiaoman.debug.RELOAD_SKILLS` | 无 | 调用 `SkillRepository.reload()` 并记录加载问题 |

示例：`adb shell am broadcast -n dev.xiaoman.app/.debug.DebugReceiver -a dev.xiaoman.debug.RUN --es goal "打开设置" --es run_id r1`

日志：logcat 标签 `小满`，每个事件一行：`小满: <run_id> <EventKind> <json>`。

轨迹：`<external files>/traces/<run_id>/trace.jsonl`，每行一个 JSON 对象：

```json
{"time": 1761830000000, "run": "r1", "kind": "StepPlanned", "step": 3, "action": {"type": "tap", "nodeId": 12}, "text": null, "ok": null, "decision": null}
```

`kind` 是 `AgentEvent` 的类名。`text` 存放 Reply / NeedsUser / finish 的摘要和失败信息；`ok` 存放
`ActionResult.ok` 或 `success`；`decision` 为 `allow|ask|deny: <原因>`。每一步的截图保存在同目录下的
`step-<n>.png`。

## 模型

任意兼容 OpenAI 接口的服务都可以。需要两组设置：聊天模型和操作（读屏）模型，后者必须支持图片输入。
以火山方舟为例：Base URL 为 `https://ark.cn-beijing.volces.com/api/v3`；开发机上 Key 放在环境变量
`ARK_API_KEY` 中，手机上则填写到「设置」里。API Key 不会写入日志、轨迹或记忆。

## 构建

`scripts/gradle.sh <tasks>` 会把构建产物放到仓库目录之外。需要 JDK 17 和 Android SDK 35。
