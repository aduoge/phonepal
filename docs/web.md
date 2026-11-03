# 网页版：iPhone 不装 App 也能用

iPhone 不允许一个 App 操作其他 App，没有付费开发者账号也发不了 TestFlight。网页版绕开这两个限制：用 Safari 打开链接，点「分享 → 添加到主屏幕」，之后像 App 一样从桌面打开。安卓和电脑的浏览器同样能用。

网页版不操作手机屏幕，靠 [MCP 服务](mcp.md) 办事：在「服务」页勾选高德、滴滴等，或者添加任意一个 https 的 MCP 服务。

## 能做什么

| 功能 | 说明 |
|---|---|
| 聊天 | 模型用自己的方舟 Key（或任意兼容 OpenAI 接口的服务） |
| 一键勾选服务 | 「服务」页列出内置目录，勾选后填 Key；「推荐组合」一键勾选高德和 AI 热点；「测试连接」显示每个服务连上了几个工具 |
| 接入其他 MCP | 填名称、https 地址、可选 Authorization 头，选择调用前什么时候确认 |
| 调用前确认 | 叫车、下单等写操作会弹出确认卡片，点「允许」才执行，点「拒绝」或 3 分钟不处理就取消 |
| 提醒 | 网页不能在后台定时弹通知，提醒会生成「添加到日历」按钮，交给 iPhone 日历按时提醒 |
| 跳转 App | 回复里的链接和高德、淘宝等 App 跳转链接可以直接点 |

做不到的：操作手机上的 App（这需要安卓版或 Mac + WDA）；记忆只在当前会话里，服务器重启或会话 2 小时不用就会清空。

## Key 放在哪里

模型 Key 和服务 Key 只保存在这台手机的浏览器里，每次对话随请求发给 PhonePal 服务器转发，服务器只在内存里使用，不写磁盘、不写日志。不要在别人的手机或公共电脑上填写 Key；用完可以在「设置 → 清除所有本地数据」里删除。

## 部署

服务端是 `web-server` 模块，一个 Java 17 程序，网页文件已经打包在里面。

```bash
./gradlew -Pphonepal.iosOnly=true :web-server:installDist
web-server/build/install/web-server/bin/web-server --host 0.0.0.0 --port 8787 --access-code 课堂口令
```

- 必须放在 HTTPS 后面（例如 Nginx、Caddy 或云平台的 HTTPS 入口）。iPhone 添加到主屏幕和浏览器保存 Key 都依赖 HTTPS。
- `--access-code` 设置访问码，学员第一次打开时输入；不设置则任何人拿到链接都能用你的服务器转发请求。
- 服务器不会访问内网地址：模型地址和 MCP 地址解析到 10.x、192.168.x、127.0.0.1、169.254.x 等都会被拒绝。`--allow-private` 只用于本机测试。
- 其他参数：`--timezone Asia/Shanghai`（提醒时间换算）、`--session-hours 2`（会话空闲多久清空）。`web-server --help` 查看全部。

## 接口

前端与服务器同源，接口定义在 `web-server/src/main/resources/web/app.js` 顶部注释里：`/api/info`、`/api/catalog`、`/api/session`、`/api/chat`（SSE 事件流）、`/api/answer`、`/api/mcp/test`。
