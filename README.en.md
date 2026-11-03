# PhonePal

[中文](README.md)

An open-source personal agent that lives on an Android phone. You chat with it in its app; it
remembers what you tell it and operates the other apps on the phone for you (打车、点外卖、改设置 ...).
New scenarios are **skills**: small `SKILL.md` + `flow.yaml` folders you can push without rebuilding
the APK.

- Android 11 (API 30) or newer. It uses an accessibility service to read the screen and act.
- iOS cannot run it on the phone alone (one app may not operate other apps). You can try it from a Mac
  that drives the iPhone through WebDriverAgent; see [docs/ios.md](docs/ios.md) (Chinese).
- Models: any OpenAI-compatible endpoint (Volcano Ark, Bailian, OpenRouter, vLLM, Ollama ...). You
  configure a chat model and a screen ("operator") model that accepts images.

## Safety

Every action passes the Sentinel policy before it reaches the phone, outside the model's control:
typing into password fields and acting on secure (FLAG_SECURE) screens are denied; taps on things
like 支付 / 发送 / 删除 / 确认呼叫 need your approval; a skill may only operate the apps it lists.
Skills can add confirmation words but never remove the built-in ones. Every decision is audited.

## Build

JDK 17 and Android SDK 35.

```
scripts/gradle.sh --online test :app:assembleDebug   # first run fetches dependencies
scripts/gradle.sh test :app:assembleDebug            # later runs work offline
```

`scripts/gradle.sh` wraps `./gradlew` and keeps build output outside the checkout; see the script
header for its environment variables.

## Use

1. Install the debug APK and open PhonePal.
2. Settings: enter base URL, model and API key for the chat and operator models.
3. Turn on the PhonePal accessibility service when the app asks.
4. Chat: "帮我打车回家", "记住我家在 ...", "把 Wi-Fi 打开".

## Skills and developer CLI

- Format: [docs/skills.md](docs/skills.md); examples in [skills/examples/](skills/examples/).
- [cli/](cli/README.md) (Python 3, stdlib only) lints skills, pushes them over adb, runs tasks on a
  debug build and shows traces:

```
python3 cli/phonepal.py lint skills/examples/*
python3 cli/phonepal.py push skills/examples/taxi-home
python3 cli/phonepal.py run "打开设置里的 Wi-Fi 页面" --skill open-settings-wifi --follow
python3 cli/phonepal.py traces show <run_id>
```

## Layout

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): `core-*` are plain Kotlin/JVM modules (contracts,
LLM client, memory, skills, sentinel, agent), `hands` is the accessibility layer, `app` the UI.

## Memory

Notes are Markdown files in the Basic Memory format under
`/sdcard/Android/data/dev.phonepal.app/files/memory/`, so you can read and edit them in Obsidian.
Forgetting deletes the file.

## License

MIT, see [LICENSE](LICENSE).
