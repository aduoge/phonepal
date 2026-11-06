# 小满网页版：iPhone 不装 App 也能用

iPhone 不允许一个 App 操作其他 App，没有付费开发者账号也发不了 TestFlight。小满网页版绕开这两个限制：用 Safari 打开链接，点「分享 → 添加到主屏幕」，之后像 App 一样从桌面的小满图标打开。界面配色、图标与安卓 App 一致，安卓和电脑的浏览器同样能用。

网页版不操作手机屏幕，靠 [MCP 服务](mcp.md) 办事：在「服务」页勾选高德、滴滴等，或者添加任意一个 https 的 MCP 服务。

## 能做什么

| 功能 | 说明 |
|---|---|
| 聊天 | 模型用自己的方舟 Key（或任意兼容 OpenAI 接口的服务）；服务器开启课堂令牌时，也可以只填讲师发的课堂令牌 |
| 一键勾选服务 | 「服务」页列出内置目录，勾选后填 Key；「推荐组合」一键勾选高德和 AI 热点；「测试连接」显示每个服务连上了几个工具 |
| 接入其他 MCP | 填名称、https 地址、可选 Authorization 头，选择调用前什么时候确认 |
| 调用前确认 | 叫车、下单等写操作会弹出确认卡片，点「允许」才执行，点「拒绝」或 3 分钟不处理就取消 |
| 提醒 | 网页不能在后台定时弹通知，提醒会生成「添加到日历」按钮，交给 iPhone 日历按时提醒 |
| 记忆 | 说「记住我喜欢燕麦拿铁」，小满会记下来；刷新页面、服务器重启后仍在。「设置 → 小满的记忆」里可以查看、删除单条或清空 |
| 跳转 App | 回复里的链接和高德、淘宝等 App 跳转链接可以直接点 |

做不到的：操作手机上的 App（这需要安卓版或 Mac + WDA）；网页不能后台运行，关掉页面后不会主动提醒（提醒请添加到日历）。

## 记忆保存在哪里

记忆只保存在学员自己的浏览器里（localStorage 的 `xiaoman.memory`），服务器不落盘：

1. 每次发消息，网页把本地记忆（最新的 200 条、约 180KB 以内）随请求一起发给服务器。
2. 服务器把它们放进本轮对话的内存记忆里，模型照常检索、引用（与安卓版相同的 Basic Memory Markdown 格式）。
3. 模型「记住」或「忘记」时，服务器发回 `memory` 事件（`saved` 带完整笔记，`deleted` 带 permalink），网页写入本地。
4. 会话过期、服务器重启只会丢掉对话上下文，记忆随下一条消息带回。

所以换手机、换浏览器、清除 Safari 网站数据或点「清除所有本地数据」后，记忆也就没了。会话（对话上下文）仍在服务器内存里，空闲 2 小时（`--session-hours`）清空。

## Key 放在哪里

模型 Key 和服务 Key 只保存在这台手机的浏览器里，每次对话随请求发给小满服务器转发，服务器只在内存里使用，不写磁盘、不写日志。不要在别人的手机或公共电脑上填写 Key；用完可以在「设置 → 清除所有本地数据」里删除。

## 课堂令牌

没有方舟账号的学员可以用讲师发的课堂令牌（`xm-` 开头）。服务器开启后，「设置 → 模型设置」会出现「使用课堂令牌」：只填令牌，服务器用讲师的 Key 调用白名单里的模型（`XIAOMAN_CLASS_WEB_MODEL`，默认白名单第一个），和安卓 App 走 `/v1` 共用同一套记账、每日/总量上限和限流。令牌只存在浏览器本地（`xiaoman.model`），原来的「自带 Key」模式不变。

服务端同时提供 OpenAI 兼容接口 `POST /v1/chat/completions`（支持 `stream` SSE、图片输入）和 `GET /v1/models`，用 `Authorization: Bearer <令牌>` 认证，给安卓 App 使用：Base URL 填 `https://<网址>/v1`，Key 填令牌，模型名填白名单名。

开启方式（默认关闭）：配置 `XIAOMAN_CLASS_UPSTREAM_KEY_FILE`（或 `XIAOMAN_CLASS_UPSTREAM_KEY`）、`XIAOMAN_CLASS_MODELS`（如 `xiaoman-class=doubao-seed-1-6-250615`），可选 `XIAOMAN_CLASS_UPSTREAM_URL`（默认方舟北京），然后用 `web-server tokens generate N` 生成令牌文件（`--class-tokens` 或 `XIAOMAN_CLASS_TOKENS` 指定路径，默认 `/var/lib/xiaoman-web/class-tokens.json`）。令牌管理、课前课后操作见 [deploy/README.md](../deploy/README.md#课堂令牌讲师-key-兜底)。

课堂请求默认关闭豆包深度思考（`XIAOMAN_CLASS_THINKING=disabled`）：思考内容按输出价计费，关掉后同样的任务输出 Token 少一半以上。可设 `enabled`、`auto`，或 `keep` 原样转发；请求里自带 `thinking` 时以请求为准。

## 部署

服务端是 `web-server` 模块，一个 Java 17 程序，网页文件已经打包在里面。上云服务器用 [deploy/](../deploy/README.md) 里的一键脚本：Caddy 自动 HTTPS（自有域名或 `<IP>.sslip.io`）、systemd 服务、访问码和时区、更新与回滚。

```bash
deploy/install.sh ubuntu@203.0.113.10 --sslip --install-deps        # 没有域名
deploy/install.sh ubuntu@203.0.113.10 --domain xiaoman.example.com  # 自有域名
```

手动运行：

```bash
./gradlew -Pxiaoman.iosOnly=true :web-server:installDist
XIAOMAN_WEB_ACCESS_CODE=课堂口令 web-server/build/install/web-server/bin/web-server --host 127.0.0.1 --port 8787
```

- 必须放在 HTTPS 后面（Caddy、Nginx 或云平台的 HTTPS 入口），web-server 本身只监听本机。iPhone 添加到主屏幕和浏览器保存数据都依赖 HTTPS。
- 访问码：`XIAOMAN_WEB_ACCESS_CODE` 或 `--access-code`，学员第一次打开时输入；不设置则任何人拿到链接都能用你的服务器转发请求。
- 服务器不会访问内网地址：模型地址和 MCP 地址解析到 10.x、192.168.x、127.0.0.1、169.254.x 等都会被拒绝。`--allow-private` 只用于本机测试。
- 写操作确认、Key 只存浏览器、日志只记接口名和状态码，这些都不需要额外配置。
- 其他参数：`--timezone Asia/Shanghai`（提醒时间换算）、`--session-hours 2`（会话空闲多久清空）、`--stdio-mcp auto|on|off`、`--max-stdio 20`。`web-server --help` 查看全部。

## 12306 火车票（需要 Node.js）

目录里的 12306 是社区 stdio 服务（`npx -y 12306-mcp`），要在服务器上起子进程：

- `--stdio-mcp auto`（默认）：PATH 里同时有 `node` 和 `npx` 才开启；`on` 强制开启；`off` 关闭。启动日志会打印当前状态，`/api/info` 的 `stdioMcp` 字段也会告诉网页。
- 未开启时，「服务」页仍列出火车票查询，但开关是灰的，并写明“服务器没有开启这项服务（需要 Node.js）”。
- 开启时每个勾选它的会话一个 Node 进程，会话结束即关闭，同时最多 `--max-stdio` 个；子进程只继承 PATH、HOME、代理等少数环境变量，拿不到访问码。只有内置目录里的命令会被启动，学员自己添加的服务只能是 https 地址。
- 本机实测（Node 24）：第一次 `npx` 要下载包，30 秒内没连上；缓存好之后约 3 秒连上，列出 8 个工具。查询车票本身没有在课堂网络里验证。

## 接口

前端与服务器同源，接口定义在 `web-server/src/main/resources/web/app.js` 顶部注释里：`/api/info`（含 `classTokens`）、`/api/catalog`、`/api/session`、`/api/chat`（SSE 事件流，请求里带 `memory.notes`；课堂模式 `model` 为 `{classToken}`）、`/api/answer`、`/api/mcp/test`。服务器端的记忆格式见 `web-server/src/main/kotlin/dev/xiaoman/web/BrowserMemory.kt`。课堂令牌见 `ClassGateway.kt`（`/v1` 转发、限流、记账）、`ClassTokens.kt`（令牌文件）、`ClassTokenAdmin.kt`（`web-server tokens` 命令）。

## 测试

```bash
./gradlew -Pxiaoman.iosOnly=true :web-server:test :web-server:jsTest   # Kotlin 接口测试 + 网页 JS 测试（需要 Node.js）
```
