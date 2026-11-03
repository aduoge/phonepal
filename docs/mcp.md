# 接入 MCP：不操作手机也能办事

查天气、规划路线、查车票、叫车、点餐这类事，很多平台已经提供了 MCP 服务。PhonePal 可以直接调用这些服务，不用在手机上一步步点。iPhone 用户也能用，因为这条路不需要操作手机屏幕。

聊天模型会同时看到内置工具（记忆、提醒、`phone_task`）和 MCP 工具。MCP 工具名的格式是 `服务器名__工具名`，比如 `amap__maps_weather`。能用 MCP 工具完成的事，模型会优先用工具；剩下只有 App 能做的事，再交给 `phone_task` 操作手机。

## 可以接哪些服务

`mcp/servers.example.json` 里放了下面这些服务器。“实测”一列是 2026-11-03 发 MCP 握手请求的结果。

| 服务器 | 能做什么 | 来源 | 需要什么 | 实测 |
|---|---|---|---|---|
| `amap` 高德地图 | 地点搜索、路线规划、天气、大众点评数据 | 官方，远程 HTTP | 高德开放平台 Key（个人可免费申请） | 地址可达，没带 Key 时返回 INVALID_USER_KEY |
| `tencent-map` 腾讯地图 | 地点、路线、天气 | 官方，远程 HTTP | 腾讯位置服务 Key | 不带 Key 也能握手 |
| `12306` 火车票查询 | 查余票、车次 | 社区项目 `12306-mcp`，需要本机 `npx` | 无 | 未实测（需要在本机下载并运行 npm 包） |
| `didi` 滴滴出行 | 估价、**叫车 / 取消**、行程状态 | 官方，远程 HTTP | 滴滴开放平台 Key | 地址可达，提示缺少 Key；默认关闭 |
| `mcdonalds` 麦当劳 | 门店、菜单、营养信息 | 官方，远程 HTTP | 麦当劳 MCP Token | 地址可达，没带 Token 时返回 403；默认关闭 |
| `aihot` AI 热点资讯 | 每日、每周 AI 资讯，热点事件 | 第三方站点，匿名 | 无 | 已连通，`aihot_get_hot_topics` 调用成功 |

更多国内服务可查 [awesome-china-mcp](https://github.com/zackchewa/awesome-china-mcp)，比如瑞幸（点单）、滴答清单、腾讯文档、百度地图、快递100。

淘宝、美团、微信目前没有官方 MCP，这类事仍然只能靠 `phone_task` 操作 App。

## 安全规则

- **写操作先确认。** 每个服务器有 `confirm` 设置：`writes`（默认）表示除了服务器标明只读（`readOnlyHint`）的工具，其他工具每次调用前都要问你；`all` 表示全部要问；`none` 表示都不问，只适合查询类服务器。叫车、下单这类服务器保持 `writes`。
- **没人确认就不执行。** Mac 上用 `--non-interactive` 运行，或者没有终端时，需要确认的调用一律拒绝。安卓 App 会在聊天里问你，回复“确认”才会执行。
- **Key 不写进仓库。** 在 Mac 上，`mcp.json` 里只写 `${环境变量名}`，真实的 Key 放在环境变量里；缺少变量的服务器会被跳过。工具结果、提示和轨迹里出现的 Key 会被替换成 `***`。
- **外部内容只当资料。** 工具返回的内容会交给模型参考，不要把里面的指令当成你的要求。

## 在 Mac 上用（ios-runner）

```bash
cp mcp/servers.example.json ~/.phonepal-ios/mcp.json   # 按需删改服务器，把 enabled 改成 true
export AMAP_MAPS_API_KEY=你的高德Key                     # 只放在环境变量里
ios-runner tools                                        # 看哪些服务器连上了、有哪些工具
ios-runner call amap__maps_weather '{"city":"北京"}'     # 不经过模型，直接调一个工具，用来排查
ios-runner ask "明天从望京到首都机场怎么走，大概多久"
ios-runner chat
```

`tools`、`call` 不需要配置模型。`ask` 和 `chat` 要先按 [ios.md](ios.md) 配好模型。`12306` 这类 stdio 服务器要求 Mac 上装有 Node.js。

## 在安卓 App 上用

App 在启动时读取 `/sdcard/Android/data/dev.phonepal.app/files/mcp.json`。安卓上没有环境变量，Key 直接写在这个文件里；这个目录只有 App 自己和 adb 能读取。手机上只支持远程 HTTP 服务器，stdio 服务器会被跳过。

```bash
adb push mcp.json /sdcard/Android/data/dev.phonepal.app/files/mcp.json
```

改完配置需要重启 App。需要确认的工具会在聊天里提问，回复“确认”才会执行，回复其他内容就取消。

## 实现位置

- `core-api/.../Tools.kt`：`ToolProvider`、`ToolResult`、`ToolApprovalGateway` 接口。
- `core-agent`：把外部工具和内置工具一起交给聊天模型；每次调用都会发出 `AgentEvent.ToolCalled`，聊天界面和轨迹会显示。
- `core-mcp`：MCP 客户端，支持 Streamable HTTP（JSON 和 SSE 两种响应）和 stdio；包括配置解析、工具名映射、确认策略、结果截断、密钥脱敏，失败的服务器 5 分钟后再重试。
- 接入点：`ios-runner` 的 `Assembly.kt`、安卓的 `AppGraph.kt`。
