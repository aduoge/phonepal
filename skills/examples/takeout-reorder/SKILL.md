---
name: takeout-reorder
description: 在美团外卖里把上一单再来一单（提交订单和支付前需用户确认）
apps: [com.sankuai.meituan, com.sankuai.meituan.takeoutnew]
channel: gui
confirm_before: [提交订单, 支付]
memory: [偏好/外卖]
---
# 美团外卖再来一单

目标：找到最近一笔已完成的外卖订单，原样再下一单，停在提交订单前。

1. 使用已安装的入口：美团主 App（com.sankuai.meituan）先进入「外卖」，独立版为 com.sankuai.meituan.takeoutnew；再进入外卖「订单」页。不能把主 App 的其他订单当作外卖订单。
2. 找到最近一笔已完成订单，点「再来一单」；没有这个按钮时进入店铺，按原订单的菜品逐个加入购物车。
3. 进入结算页后核对：收货地址、菜品和数量与上一单一致，读出总价和预计送达时间。
4. 地址不是默认地址、有菜品售罄或店铺打烊时，用 ask_user 说明情况，让用户决定。
5. 「提交订单」和「支付」都必须先得到用户确认；用户确认支付后，到支付页面就交给用户完成，不要输入密码。

完成后 finish，summary 写店铺名、菜品、总价和预计送达时间。
