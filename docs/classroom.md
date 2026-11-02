# 用 TRAE 给 PhonePal 加能力：学生动手手册

你会先在手机上完成一件小事，再用 TRAE 修改一份技能说明，让 PhonePal 按你的要求做事。最后交一份 SKILL.md 和一段能看清结果的录屏。

## 课前把这几样准备好

电脑安装 TRAE IDE、Python 3.8+ 和 Android platform-tools。手机需要 Android 11 及以上，安装讲师提供的 PhonePal debug APK。iPhone 不能完成同样的跨 App 操作，可与有安卓手机的同学结对。

在 PhonePal「设置」里填写自己的聊天模型和操作模型：Base URL、模型名或接入点 ID、API Key。操作模型需要支持图片。方舟示例地址是 `https://ark.cn-beijing.volces.com/api/v3`；2026-11-02 的讲师实验使用 `doubao-seed-2-1-pro-260915`，自己的账号也要开通相应模型。Key 只填在 App 中，不发到群里或写进技能文件。

按 App 提示开启无障碍服务和通知权限。USB 连接电脑与手机，在手机上允许本电脑调试。在 TRAE 终端运行：

```text
python --version
adb devices -l
```

macOS 若没有 `python`，下面所有命令改用 `python3`。设备状态要显示 `device`；显示 `unauthorized` 时在手机上确认调试授权，显示 `offline` 时重新连接。多台设备连接时，给 PhonePal 命令加 `--serial 你的设备序列号`。

把课程源码解压，使用 TRAE 打开包含 README.md、cli、skills 的 PhonePal 文件夹。终端也要位于这个文件夹。远程 Home Mac 中转只是讲师环境，学生使用 USB 即可。

## 第一节：先用起来，再改一点点

打开 PhonePal 的聊天页，输入：

> 打开 WLAN 设置，告诉我 Wi-Fi 是否开启、当前连接哪个网络。保持设置不变。

观察手机是否进入 WLAN 页面，把助手的回答与页面核对。看到“完成”两个字还不够；页面上的连接状态才是这一步的结果。

然后回到电脑，在 TRAE 输入：

> 请阅读 README.md、docs/skills.md 和 open-settings-wifi 示例。用零基础能懂的话解释：我发一句话以后，聊天模型、技能和手机操作分别做了什么。先不要改代码。

让 TRAE 帮你做第一次修改：

> 请修改 open-settings-wifi 的结果要求，让它用两行报告“Wi-Fi 状态”和“当前网络”。只读取设置。连接不上或页面没有网络名时如实说明，不能猜。保留技能的 apps 和 channel。改完运行技能校验，并说明你改了哪几行。

在 TRAE 终端执行：

```text
python cli/phonepal.py lint skills/examples/open-settings-wifi
python cli/phonepal.py push skills/examples/open-settings-wifi
```

`lint` 通过会显示 `0 problem(s)`；`push` 成功后会显示 `skills reloaded`。回到 PhonePal 再发一次相同任务，检查回答是否按你的要求变化。技能已经推到手机，电脑上的 TRAE 不需要一直开着。

你刚完成了一轮需求、修改、检查、使用。SKILL.md 可以理解为“给助手的操作说明”；修改说明后可以直接推送。APK 是手机上运行的程序，改变程序能力需要重新构建并安装。

## 第二节：做一个新技能，遇到问题就带证据修

讲师用 set-alarm 展示新技能。它只新增测试闹钟，避免改动已有闹钟。不同品牌的时钟页面差别较大，暂时不适配时继续完善第一节技能，也能完成课程作业。

给 TRAE 的需求：

> 请先读 README.md、docs/skills.md 和现有技能，帮我新增一个用系统时钟设置闹钟的 set-alarm 技能。时间由我指定，没说清日期或上午下午时要追问。只新增，不改已有闹钟。支持我的手机品牌，包名要通过已连接手机核对。保存前后检查日期、时间和重复规则。时间滚轮接近目标时逐格微调，每次读取选中值，不要反复大幅滑动。文件已存在时先解释现有版本，再只修改需要改的地方。写完运行 lint，解释成功和失败分别怎么看。

示例已经包含 vivo 的 `com.android.BBKClock`，原生 Android、Pixel 的包名可能分别为 `com.android.deskclock`、`com.google.android.deskclock`。这份列表不覆盖所有品牌。`apps` 写错时，可能出现启动失败或技能的应用范围检查拒绝；把实际包名与日志交给 TRAE 修改。

推到手机：

```text
python cli/phonepal.py lint skills/examples/set-alarm
python cli/phonepal.py push skills/examples/set-alarm
```

在 PhonePal 聊天里输入明确的日期和时间，例如“新建今天下午某个尚未到达时间的一次性闹钟，标签是课堂测试”。先把日期和时间替换为自己当下的未来时间，预留至少 15 分钟操作与清理，不直接照抄讲师历史测试时间。

需要看每一步时，可以单独运行操作层：

```text
python cli/phonepal.py run "你的完整闹钟要求，包含日期、时间和测试标签" --skill set-alarm --follow
python cli/phonepal.py traces show 本次运行ID
```

聊天发起会同时检验“模型是否选对技能”；CLI 的 `run` 只检查操作层。两种结果不能混在一起。每次使用新运行 ID，默认自动生成即可。

成功时要在时钟 App 中看到新增的时间、标签、仅一次和开启状态；保存后自己核对。练习结束，只删除自己刚创建、带测试标签的闹钟，确认其余闹钟保持原样。遇到执行失控时点击 PhonePal 的“停止”，或运行：

```text
python cli/phonepal.py stop
```

把问题交回 TRAE 时，用这段话：

> 我的目标是……，实际停在……。这是本次运行 ID、最后几步日志和当前截图。请先解释失败发生在哪一步，再修改最小范围。保留成功标准，不能把没做完改成成功。修改后给我重新校验、推送和验证的步骤。

## 看 TRAE 增加一个程序功能

讲师演示的例子是 reminder_set：在聊天里说“2 分钟后提醒我喝水”，App 安排系统通知。它使用 PhonePal 自己的通知，不需要进入系统时钟。

TRAE 的开发需求可以这样写：

> 请先阅读项目的聊天工具、模块边界和通知实现。增加 reminder_set，参数为提醒内容和 ISO 本地日期时间。核心模块不要依赖 Android；由 app 模块安排定时通知。时间格式错误或已经过去时要返回错误。给聊天模型提供当前时间，便于理解“几分钟后”。写有意义的测试，运行测试和 APK 构建，最后解释如何在真机上验证。使用非精确排程时，要明确系统可能延迟，当前不要求重启恢复。

有 JDK 17、Android SDK 35 的同学可课后复现构建。命令的前提是电脑已经安装并配置好这些环境；首次依赖下载留在课前：

```text
scripts/gradle.sh --online test :app:assembleDebug
```

Windows 需要可执行该 shell 脚本的环境；本轮没有完成 Windows 构建实测。普通学员使用课程 APK 即可。讲师本轮使用的是 TRAE CLI；TRAE IDE 的课堂演示另行预演。

安装包含提醒功能的课程 APK 后，允许通知，在聊天里安排一条带自己测试标记的提醒。若需要后台提醒，先完成机型对应的后台权限设置。在本轮 vivo 上，路径是系统设置的“后台耗电管理”→“PhonePal”→“允许后台高耗电”。其他品牌名称可能不同。

离开 PhonePal 后观察系统通知。2026-11-02 的 vivo 实验中，16:19 的提醒在 16:20:04 创建通知；验收时允许观察一段延迟。当前版本没有提醒列表、取消入口、重启恢复，不能把它用于必须准时的关键提醒。模型说“已设置”只表示已调用排程，最终还要看到通知。

## 作业怎么交

交一份修改后的技能文件、一段手机实际操作录屏，再用三句话写清：想解决什么、怎样确认做到了、还有哪种情况没解决。遇到失败也可以交运行轨迹和一次有依据的修改；课程要练的是把问题说清、验证结果和继续修正。
