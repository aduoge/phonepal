# PhonePal

[English](README.en.md)

PhonePal 是一个跑在 Android 手机上的开源私人助手。你在它的 App 里聊天，它会记住你告诉它的事，
也能替你操作手机上的其他 App，比如打车、点外卖、改系统设置。新场景以**技能**的形式添加：一个技能
就是一个包含 `SKILL.md` 和可选 `flow.yaml` 的小文件夹，推到手机上即可生效，不需要重新打包 APK。

- 需要 Android 11（API 30）及以上。PhonePal 通过无障碍服务读取屏幕并执行操作。
- iOS 无法在手机上单独运行；可用 Mac + WebDriverAgent 的方式试用，见 [docs/ios.md](docs/ios.md)。
- 模型：任意兼容 OpenAI 接口的服务都可以，例如火山方舟、阿里云百炼、OpenRouter、vLLM、Ollama。
  需要配置两个模型：一个聊天模型，一个能看图的屏幕操作模型。

## 工作方式

```
你：帮我打车回家
 │
 ▼
聊天模型 ── 查记忆（家在哪）、挑技能（taxi-home）
 │
 └─ phone_task ──► 操作循环：截屏 + 节点树 → 操作模型给出一步 → Sentinel 审核 → 执行
                     │                                        │
                     │                         支付 / 发送 / 删除等先问你
                     ▼
               结果摘要回到聊天；成功的操作路径写入记忆，下次执行时参考
```

## 安全

每一步操作在真正落到手机之前，都要经过 Sentinel 策略检查。这一层在模型之外，模型无法绕过：

- **拒绝**：向密码框输入；在受保护（FLAG_SECURE）的页面上操作；技能声明了 `apps` 时操作其他 App。
- **询问**：点击「支付」「发送」「删除」「确认呼叫」等敏感按钮前，需要你确认（允许一次 / 本次任务都允许 / 拒绝）。
- 技能只能**追加**确认词，不能删除内置确认词。
- 每一次决策都会写入审计记录。

## 构建

需要 JDK 17 和 Android SDK 35。

```bash
scripts/gradle.sh --online test :app:assembleDebug   # 首次运行，会下载依赖
scripts/gradle.sh test :app:assembleDebug            # 之后可以离线构建
```

`scripts/gradle.sh` 封装了 `./gradlew`，构建产物放在仓库目录之外，保持工作区干净。可用的环境变量见脚本开头的注释。

## 使用

1. 安装 debug APK，打开 PhonePal。
2. 在「设置」里分别填写聊天模型和操作模型的 Base URL、模型名称和 API Key。
3. 按提示开启 PhonePal 无障碍服务。
4. 回到「聊天」，试试：「帮我打车回家」「记住我家在……」「把 Wi-Fi 打开」。

以火山方舟为例，Base URL 填 `https://ark.cn-beijing.volces.com/api/v3`（也是默认值），模型填方舟上的模型或接入点 ID。

## 课堂实践

[用 TRAE 给 PhonePal 加能力](docs/classroom.md)：围绕美团点餐与12306选票，用 TRAE 修改技能、推送验证，再体验行程提醒。

## 技能与开发者 CLI

- 技能格式见 [docs/skills.md](docs/skills.md)，示例在 [skills/examples/](skills/examples/)。
- [cli/](cli/README.md)（Python 3，仅依赖标准库）可以校验技能、通过 adb 推送到手机、在 debug 包上直接跑任务并查看轨迹：

```bash
python3 cli/phonepal.py lint skills/examples/*
python3 cli/phonepal.py push skills/examples/taxi-home
python3 cli/phonepal.py run "打开设置里的 Wi-Fi 页面" --skill open-settings-wifi --follow
python3 cli/phonepal.py traces show <run_id>
```

## 目录结构

```
phonepal/
├── core-api/        # 接口与数据结构，其他模块只依赖它
├── core-llm/        # 兼容 OpenAI 的模型客户端
├── core-memory/     # 记忆：Basic Memory 格式的 Markdown 笔记 + 中文检索
├── core-skills/     # 技能加载与校验（SKILL.md / flow.yaml）
├── core-sentinel/   # 安全策略：允许 / 询问 / 拒绝 + 审计
├── core-agent/      # Agent：聊天编排、操作循环、技能流程、经验沉淀
├── hands/           # Android 无障碍层：读屏、点击、输入、悬浮停止按钮
├── ios-hands/、ios-runner/ # iOS WDA 设备接口 / Mac JVM 命令行（使用说明见 docs/ios.md）
├── app/             # Android App：聊天、技能、记忆、设置界面
├── cli/             # 技能开发者 CLI
├── skills/examples/ # 示例技能，也会打包进 APK
├── docs/            # 架构与技能文档
└── scripts/         # 构建脚本
```

`core-*` 都是纯 Kotlin/JVM 模块，不依赖 Android API，可以在任何机器上跑单元测试。详细设计见
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 记忆

记忆是 Basic Memory 格式的 Markdown 文件，存放在
`/sdcard/Android/data/dev.phonepal.app/files/memory/`，可以直接拷进 Obsidian 查看和编辑。
「忘记」会直接删除对应文件。

## 许可证

MIT，见 [LICENSE](LICENSE)。
