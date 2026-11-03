# 在 Mac 上使用 PhonePal 操作 iPhone

iOS 限制 App 读取和操作其他 App 的界面，因此无法像 Android 那样，在 iPhone 上装一个 PhonePal App 就直接操作淘宝、设置等应用。本方案需要一台 Mac，通过 Apple 的 UI 测试工具控制你自己的 iPhone。

Mac 上的 JVM 命令行 `ios-runner` 负责聊天、模型请求、记忆、技能、安全确认和 Agent 操作循环，复用 `core-*` 模块。`ios-hands` 把点击、输入、截图等请求交给 iPhone 上的 WebDriverAgent（下文简称 WDA）。WDA 是 Appium 维护的 XCTest UI 测试 runner，由 Xcode 构建、签名并在手机上运行。

```text
Mac：ios-runner → 模型与安全检查 → ios-hands
                                  ↓ HTTP，127.0.0.1:8100
                             USB 端口转发
                                  ↓
iPhone：WDA（8100）→ 当前 App 的界面
```

Mac 负责决策并发出操作，WDA 在 iPhone 上执行。运行期间手机屏幕会被测试控制，Mac、数据线和手机都需要保持连接。这是一套开发试用方案；`ios-runner` 命令与配置的实际支持范围以 `ios-runner --help` 为准。

## 先准备好这些

- 一台可以安装兼容 Xcode 的 Mac。先看下面的版本表，避免下载后才发现系统不支持。
- 完整的 Xcode，只有 Command Line Tools 不够。首次打开 Xcode，按提示完成组件安装与许可确认。
- Apple ID，用于在 Xcode 签名 WDA。免费个人账号的签名通常 7 天过期，需要重新构建签名。脚本只提示你登录，不处理账号凭据。
- 一部自己的 iPhone 和能传数据的 USB 数据线。手机解锁后选择「信任此电脑」。在「设置 → 隐私与安全性 → 开发者模式」开启开发者模式，按手机提示重启并确认；如果看不到该选项，先连接 Xcode。
- JDK 17，确认终端的 `java -version` 使用它。
- 方舟模型 API Key，或其他兼容 OpenAI 接口服务的 Key。准备聊天模型和支持图片输入的操作模型。

| 手机 / Xcode | 至少需要 |
|---|---|
| iOS 17.4 及以上、低于 iOS 18 | Xcode 15.3 |
| iOS 18 | Xcode 16 |
| iOS 26 | Xcode 26 |
| Xcode 16.x | macOS 14.5 |
| Xcode 26.0–26.3 | macOS 15.6 |
| Xcode 26.4 及以上 | macOS 26.2 |

这是 `doctor.sh` 使用的最低版本提示规则。具体 Xcode 小版本支持的 SDK 和系统范围，仍需核对 Apple 对该版本的说明。较新的 iOS 小版本可能需要更新的 Xcode。

## 第一次启动

以下脚本命令在仓库根目录的 Mac 终端执行。两个脚本无参数或带 `--help` 时只显示用法，不下载软件、不运行 Xcode。实际检查要使用 `doctor.sh check`。

### 1. 检查电脑和手机

```bash
bash scripts/ios/doctor.sh check
```

脚本会显示 macOS、Xcode 路径与版本、支持的 iOS SDK、连接设备及其 iOS 版本、Java 版本，并访问 WDA `/status`。第一次尚未启动 WDA 时，连接失败是正常的。它只做检查，不会替你安装软件或更改系统设置。

### 2. 在 Xcode 登录并确定签名团队

打开 Xcode，在「Settings → Accounts」添加 Apple ID。选择你的账号和 Team，记下 Team ID。免费账号通常显示为 Personal Team；如果看不到 ID，可以在 Xcode 的项目「Signing & Capabilities」中确认所选团队。不要把 Apple ID 密码交给脚本。

在当前终端设置团队与自己的唯一 bundleId：

```bash
export TEAM_ID="你的TeamID"
export WDA_BUNDLE_ID="com.yourname.phonepal.WebDriverAgentRunner"
# 多台设备连接时，请从 doctor 的设备列表复制目标设备 UDID：
# export UDID="设备UDID"
bash scripts/ios/wda.sh setup
```

未设置 `UDID` 时，脚本从 `xcrun xctrace list devices` 选择第一台已连接真机，排除模拟器和 Mac 本机。多设备场景请明确指定。

`setup` 从 `https://github.com/appium/WebDriverAgent` 下载固定 tag（默认 `v16.15.3`），通过 `xcodebuild build-for-testing` 构建 WebDriverAgentRunner，并允许 Xcode 更新签名配置。可以设置 `WDA_REF` 使用与你的 Xcode/iOS 兼容的 tag。所有脚本管理的源码、构建产物、临时文件、日志、虚拟环境和 PID 都在 `${PHONEPAL_IOS_HOME:-$HOME/.phonepal-ios}/wda/` 下。更换设备或签名、签名过期后，先 `stop` 再重新 `setup`。

### 3. 在手机信任开发者证书

打开 iPhone「设置 → 通用 → VPN 与设备管理」，在开发者 App 项中选择相应证书并信任。不同 iOS 版本的文字可能稍有不同；如果尚未出现该项，先确认构建安装成功，或尝试启动 WDA 后再查看。开发者模式也要保持开启。

### 4. 启动 WDA 并转发端口

```bash
bash scripts/ios/wda.sh start
bash scripts/ios/wda.sh forward
bash scripts/ios/wda.sh status
```

`start` 后台执行 `xcodebuild test-without-building`，日志为 `wda/xcodebuild.log`。它等待日志中的 `ServerURLHere` 或可访问的 `/status`，默认最多 120 秒；较慢设备可设置 `WDA_START_TIMEOUT=240`。成功时显示日志里的设备 URL。超时后进程可能仍在运行，先看日志，再决定是否 `stop`。

`forward` 将 Mac 的 `127.0.0.1:8100` 转发到所选 iPhone 的 8100 端口。已安装 `iproxy` 时优先使用它；没有时会在 `wda/venv/` 创建 Python 3 虚拟环境并从 PyPI 安装 `pymobiledevice3`，首次需要网络。转发日志为 `wda/forward.log`。本机 8100 被占用时：

```bash
export WDA_PORT=8101
bash scripts/ios/wda.sh forward
export PHONEPAL_WDA_URL=http://127.0.0.1:8101
```

已有转发进程时先执行 `stop`，再用新端口重新 `start` 和 `forward`。设备端口固定为 8100。也可以设置 `PHONEPAL_WDA_URL=http://<设备Wi-Fi-IP>:8100`，跳过 USB 转发；只在可信网络使用，勿把 WDA 端口公开到互联网。USB 连接通常更稳定。

### 不插线使用（同一局域网）

数据线只在第一次必需：配对、信任电脑、构建和安装 WDA 都要插线完成。之后可以拔线，条件是 Mac 和 iPhone 在同一个 Wi-Fi 下，Mac 不休眠，手机保持解锁。

1. 第一次插线时，在 Xcode「Window → Devices and Simulators」里选中这台 iPhone，勾选「Connect via network」。之后它会以网络设备出现在 `doctor.sh check` 的设备列表里。
2. 拔线后照常执行 `bash scripts/ios/wda.sh start`，不用执行 `forward`。脚本会记下手机上的 WDA 地址。
3. 让 runner 直接连手机：

```bash
export PHONEPAL_WDA_URL="$(bash scripts/ios/wda.sh url)"
bash scripts/ios/wda.sh status
ios-runner check
```

Mac 仍然必不可少：iPhone 上的 WDA 是由 Mac 上的 `xcodebuild` 拉起的测试，Mac 端进程退出、休眠或断网，WDA 也会停止。WDA 端口没有任何鉴权，只在自己家里或可信的局域网使用，不要做端口映射暴露到外网。网络连接比 USB 慢，截图和读界面每步会多花一些时间。

### 5. 配置模型与技能

`ios-runner` 的配置如下（`ios-runner --help` 里有同样的说明）：

| 配置 | 默认值 / 用途 |
|---|---|
| `PHONEPAL_WDA_URL` | `http://127.0.0.1:8100`，WDA 连接地址 |
| `PHONEPAL_HOME` | `~/.phonepal-ios`，保存 `memory/`、`skills/`、`traces/` |
| `$PHONEPAL_HOME/config.properties` | 模型 Base URL 和名称，不保存 API Key |
| `PHONEPAL_CHAT_API_KEY` | 聊天模型 Key；未设置时回退到 `ARK_API_KEY` |
| `PHONEPAL_OPERATOR_API_KEY` | 操作模型 Key；未设置时回退到 `ARK_API_KEY` |

`PHONEPAL_IOS_HOME` 只控制 WDA 脚本状态；`PHONEPAL_HOME` 控制 runner 数据。如需自定义根目录，可以将两者指向同一目录。

创建模型配置与复制技能的示例：

```bash
export PHONEPAL_HOME="${PHONEPAL_HOME:-$HOME/.phonepal-ios}"
mkdir -p "$PHONEPAL_HOME/skills"
cat > "$PHONEPAL_HOME/config.properties" <<'EOF'
chat.baseUrl=https://ark.cn-beijing.volces.com/api/v3
chat.model=你的聊天模型或接入点ID
operator.baseUrl=https://ark.cn-beijing.volces.com/api/v3
operator.model=你的看图模型或接入点ID
EOF
cp -R skills/ios/open-settings-wifi "$PHONEPAL_HOME/skills/"
cp -R skills/ios/taobao-pick-item "$PHONEPAL_HOME/skills/"
```

API Key 只从环境变量读取，不写入配置文件、技能、记忆或截图。可以在终端隐藏输入，避免把实际 Key 留在命令历史里：

```bash
read -r -s -p "方舟 API Key: " ARK_API_KEY
printf '\n'
export ARK_API_KEY
```

使用不同服务或不同 Key 时，分别设置 `PHONEPAL_CHAT_API_KEY` 与 `PHONEPAL_OPERATOR_API_KEY`。Base URL 与模型 ID 也需对应服务。

### 6. 检查 runner 并跑一个只读任务

先在仓库根目录构建 runner（需要 JDK 17，第一次构建要联网下载依赖；`-Pphonepal.iosOnly=true` 跳过 Android 模块，Mac 上不用装 Android SDK），并把它加到 PATH：

```bash
./gradlew -Pphonepal.iosOnly=true :ios-runner:installDist
export PATH="$PWD/ios-runner/build/install/ios-runner/bin:$PATH"
```

然后检查连接，再跑一个只读任务：

```bash
ios-runner --help
ios-runner check
ios-runner task "打开设置里的无线局域网页面，读出当前连接的网络" --skill open-settings-wifi
```

`check` 用于检查 WDA 连接、截图和节点数。技能可以像上一步那样复制到 `$PHONEPAL_HOME/skills/`，也可以不复制，直接加 `--skills skills/ios` 读取仓库里的技能目录。

需要确认的操作（付款、发送、删除等）会在终端提示 `[y=允许一次 / t=本任务内允许 / 其他=拒绝]`。用 `--non-interactive` 或没有终端时，这类操作一律自动拒绝，提问也不会等待回答。每次运行的截图和步骤记录在 `$PHONEPAL_HOME/traces/` 下，API Key 会被替换成 `***`。设置技能只读取无线局域网开关状态与当前网络，不切换开关、不连接其他网络。

也可以保存调试观察或进入聊天：

```bash
ios-runner dump --out ./ios-dump
ios-runner chat
```

`dump` 和轨迹可能包含当前页面截图及节点文字。请先关闭含有密码、Key 或私人信息的页面，分享前检查内容。

### 7. 淘宝选商品示例

先在手机上自行登录淘宝，准备好现有收货地址，再执行：

```bash
ios-runner task "在淘宝挑选一条1米USB-C数据线，合计不超过30元，数量1，核对后停在确认订单页" --skill taobao-pick-item
```

技能搜索并挑选符合预算的商品，选择普通规格和数量 1，可在获得所需安全确认后通过「立即购买」进入确认订单页。它核对商品、规格、运费和合计后停止，不点击提交订单或付款，不修改地址和购物车。验证码、滑块、登录和密码由用户接管。若现有地址缺失或商品无法配送，也交给用户处理。

### 8. 结束使用

先结束 runner 中的任务或退出聊天，然后执行：

```bash
bash scripts/ios/wda.sh stop
```

脚本依据自己的 PID 文件及记录的进程启动时间停止转发与 Xcode 进程，不按进程名批量结束其他工具。它不删除源码和日志，也不自动撤销证书。运行任务时请保持 Mac 唤醒、手机解锁，并留意终端的确认请求。

## 与 Android 的能力差异

| 能力 | Android 方案 | Mac + iOS 方案 |
|---|---|---|
| 返回 | 可用系统返回动作 | iOS 没有通用返回键，优先点击页面返回按钮或使用 App 支持的手势 |
| 启动 App | 使用 Android 包名 | 使用 iOS bundleId，如 `com.apple.Preferences` |
| 密码框 | 安全策略拒绝自动输入 | 密码输入交给用户，WDA 能否读取其他内容依页面而定 |
| 系统弹窗 | 无障碍可见范围内操作，仍受技能与策略限制 | XCTest 可能看见弹窗；技能范围、安全策略或系统限制阻止时请用户处理 |
| 后台常驻 | 手机上的 App 与服务承担 | Mac 终端与 WDA 测试进程承担，手机锁屏或测试退出会中断 |
| 提醒 | 由 Android App 的提醒实现承担 | 依赖 runner 的具体实现与 Mac 运行状态，不保证 iPhone 本地通知；以帮助说明为准 |
| 连接要求 | 日常使用可脱离电脑 | Mac 必须保持连接；USB 转发需要数据线，Wi-Fi 模式需要网络可达 |

WDA 的截图与节点读取能力受 XCTest、App 和 iOS 版本影响，不能保证覆盖 Android 所有能力。iOS 对敏感页面的保护也不同于 Android 的 `FLAG_SECURE`；不要仅凭有截图就判断页面安全。

## 安全边界

付款、转账、删除、发送消息等操作仍需人工确认。安全检查在模型之外执行，技能只能追加确认词、缩小可操作 App 范围，不能放宽内置规则。淘宝示例将任务终点设在提交订单之前，即使获得一次进入核对页的确认也不继续下单。

密码、验证码和账户验证请手动完成。API Key 不写入任何文件或截图，不将它作为聊天目标发送。屏幕和轨迹可能包含其他个人信息；只在自己的设备上使用，分享调试材料前检查内容。WDA 服务能控制手机，使用完请停止进程。

## 常见问题

### Xcode 不认设备

先解锁 iPhone，重新插拔能传数据的数据线，确认「信任此电脑」。在 Xcode「Window → Devices and Simulators」查看真机是否出现、是否仍在准备调试组件。等待准备结束，再运行 `doctor.sh check`。只有 Command Line Tools、选错 Xcode 路径或手机系统过新，都可能导致无法识别。需要切换 Xcode 时在 Xcode 的 Locations 设置中选择正确的 Command Line Tools，脚本不会替你修改系统设置。如果 `doctor.sh` 执行 `xcodebuild -version` 时提示「需要授权才能安装这些软件包」，请在 Mac 上打开一次 Xcode，按提示安装组件（需要管理员密码）。

### 签名失败

检查 Xcode 是否已登录 Apple ID、Team 是否正确、`TEAM_ID` 是否与团队一致，bundleId 是否唯一。按 Xcode 错误提示处理证书或设备注册问题，勿把账号密码写到脚本里。免费签名过期时先 `stop` 再 `setup`，并按手机提示重新信任证书。若命令行签名仍失败，可在下载的 WDA 项目中查看 WebDriverAgentRunner 的「Signing & Capabilities」。

### 启动后手机屏幕被测试接管，或锁屏后失效

WDA 通过 UI 测试执行操作，启动 runner 时屏幕被测试控制属于预期行为。运行期间保持手机解锁，暂时避免同时手动操作同一个页面。锁屏、系统弹窗、切断连接或测试退出后任务可能中断；先停止任务、手动解锁，再检查日志和 `/status`，需要时重新启动。停止使用时执行 `wda.sh stop`。脚本不会更改自动锁定设置。

### USB 端口转发失败

查看 `wda/forward.log`，确认 UDID 指向当前设备、手机已信任 Mac、WDA 已启动、本机端口没有被其他服务占用。`iproxy` 版本需要支持脚本使用的 `-u` 和 `-s` 参数。Python 路径或安装失败时检查 Python 3 与网络；pymobiledevice3 子命令会随版本变化，可用虚拟环境内的 `pymobiledevice3 usbmux forward --help` 核对。`stop` 后再重新 `forward`，端口变化时同时更新 `PHONEPAL_WDA_URL`。

### iOS 版本太新，或 WDA 启动超时

重新运行 `doctor.sh check`，按版本表升级 Mac/Xcode，并选择兼容的 `WDA_REF` 后重新 `setup`。不要反复重试不支持该 iOS 的旧 Xcode。若版本匹配，查看 `wda/xcodebuild.log` 是否提示开发者模式、证书不可信、设备锁屏或签名过期。先解决日志中的具体问题，再增加等待时间。
