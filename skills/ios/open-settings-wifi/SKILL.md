---
name: open-settings-wifi
description: 在 iPhone 设置中打开无线局域网页面，只读出开关状态和当前网络
apps: [com.apple.Preferences]
channel: gui
---
# 查看 iPhone 无线局域网

1. 用 `launch` 启动设置，bundleId 为 `com.apple.Preferences`。
2. 在设置首页找到并点击「无线局域网」（英文界面为 Wi-Fi）。如果当前在子页面，使用页面左上角的返回按钮回到首页。
3. 读取无线局域网开关状态，以及带勾选标记的当前网络名称。关闭时报告「无线局域网已关闭」；没有勾选网络时报告「未连接网络」。只报告屏幕可确认的内容，无法判断时说明原因。
4. 不切换开关，不点击任何网络，不输入密码，不连接或忘记网络。遇到权限弹窗、密码框或页面无法读取时，用 `ask_user` 请求用户接管（`takeover: true`）。
5. 用 `finish` 汇报开关状态和当前网络名称，停留在无线局域网页面。
