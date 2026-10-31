---
name: ride-home                 # matches the folder
description: 打车回家（呼叫前需确认）
apps: [com.autonavi.minimap]
channel: deeplink-then-gui
confirm_before:
  - 确认呼叫
  - 支付
memory: [地址/家, 偏好/车型]
deeplinks:
  search: "amapuri://route/plan/?dname={destination}"
---
# 打车回家

1. 用 deeplink `search` 打开路线规划。
2. 选择快车，点「确认呼叫」前让用户确认。
