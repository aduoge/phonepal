---
name: taxi-home
description: 用高德地图打车回家（读取记忆里的家庭住址，呼叫前需用户确认）
apps: [com.autonavi.minimap]
channel: deeplink-then-gui
confirm_before: [确认呼叫]
memory: [places/家]
deeplinks:
  route: "amapuri://route/plan/?sourceApplication=xiaoman&dname={destination}&dev=0&t=0"
---
# 高德打车回家

目标：在高德地图里叫一辆去「家」的车，停在呼叫前让用户确认。

1. 目的地取自记忆 `places/家` 的地址；记忆里没有地址时用 ask_user 询问，不要猜。
2. 先用 deeplink `route` 打开路线规划，`{destination}` 填家的地址；打不开时启动高德，点「打车」，在目的地输入框输入地址。
3. 在打车页选择「快车」（用户偏好记忆里有车型时按偏好），读出预估价和预计接驾时间。
4. 点「确认呼叫」前必须得到用户确认；用户拒绝就 finish 并说明已停止。
5. 呼叫成功后看到「正在为您呼叫」或司机信息即 finish，summary 里写车型、预估价和司机信息。

注意：出现登录、实名认证、验证码或支付页面时用 ask_user 交给用户处理。
