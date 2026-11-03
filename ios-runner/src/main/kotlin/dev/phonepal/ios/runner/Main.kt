package dev.phonepal.ios.runner

import dev.phonepal.agent.ScreenText
import dev.phonepal.api.AgentEvent
import dev.phonepal.api.AgentListener
import dev.phonepal.api.ScreenState
import dev.phonepal.api.StopSignal
import dev.phonepal.ios.WdaClient
import dev.phonepal.ios.WdaDevice
import dev.phonepal.llm.LlmClientFactory
import dev.phonepal.llm.LlmClients
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(Runner(stop = CtrlCStop.install()).run(args.toList()))
}

/** The `ios-runner` command line. Everything outside the JVM (env, streams, models) is injectable for tests. */
class Runner(
    private val env: Map<String, String> = System.getenv(),
    private val input: BufferedReader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8)),
    private val out: PrintStream = PrintStream(System.out, true, Charsets.UTF_8),
    private val err: PrintStream = PrintStream(System.err, true, Charsets.UTF_8),
    private val hasConsole: Boolean = System.console() != null,
    private val llm: LlmClientFactory = LlmClients.default,
    private val userHome: String = System.getProperty("user.home"),
    private val stop: CtrlCStop = CtrlCStop(),
) {
    fun run(args: List<String>): Int {
        if (args.isEmpty()) {
            err.println(USAGE)
            return 2
        }
        if (args.any { it == "--help" || it == "-h" }) {
            out.println(USAGE)
            return 0
        }
        val config = try {
            RunnerConfig.parse(args, env, userHome)
        } catch (e: UsageException) {
            err.println("参数错误：${e.message}\n")
            err.println(USAGE)
            return 2
        }
        config.warnings.forEach { err.println("注意：$it") }
        return runBlocking {
            when (config.command) {
                "check" -> withWda(config) { client, device -> check(config, client, device) }
                "dump" -> withWda(config) { client, device -> dump(config, client, device) }
                else -> agentCommand(config)
            }
        }
    }

    private suspend fun check(config: RunnerConfig, client: WdaClient, device: WdaDevice): Int {
        val status = client.status() as? JsonObject
        val ready = (status?.get("ready") as? JsonPrimitive)?.contentOrNull ?: "未知"
        val os = ((status?.get("os") as? JsonObject)?.get("version") as? JsonPrimitive)?.contentOrNull
        out.println("WDA：${config.wdaUrl}（ready=$ready${os?.let { "，iOS $it" } ?: ""}）")
        printScreen(device.observe())
        out.println("连接正常。")
        return 0
    }

    private suspend fun dump(config: RunnerConfig, client: WdaClient, device: WdaDevice): Int {
        val dir = config.outDir!!.apply { mkdirs() }
        val screen = device.observe()
        screen.screenshotPng?.let { File(dir, "screenshot.png").writeBytes(it) }
        File(dir, "source.xml").writeText(client.source())
        File(dir, "nodes.txt").writeText(screen.nodes.joinToString("\n", postfix = "\n") { ScreenText.nodeLine(it) })
        printScreen(screen)
        out.println("已保存到 ${dir.absolutePath}：screenshot.png、source.xml、nodes.txt")
        return 0
    }

    private suspend fun withWda(config: RunnerConfig, block: suspend (WdaClient, WdaDevice) -> Int): Int {
        val client = WdaClient(config.wdaUrl)
        return try {
            block(client, WdaDevice(client))
        } catch (e: Exception) {
            err.println("连不上 WDA（${config.wdaUrl}）：${e.message}")
            err.println("请先在 Mac 上运行 scripts/ios/wda.sh start（插线时再运行 forward），再用 scripts/ios/wda.sh status 确认；手机需保持解锁。")
            1
        }
    }

    private fun printScreen(screen: ScreenState) {
        out.println("屏幕：${screen.widthPx}x${screen.heightPx} 点，前台 App：${screen.packageName ?: "未知"}")
        val shot = screen.screenshotPng
        val size = shot?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
        out.println("截图：" + when {
            shot == null -> "无"
            size == null -> "无法解码"
            else -> "${size.width}x${size.height}，${shot.size} 字节"
        })
        out.println("节点：${screen.nodes.size} 个")
        screen.nodes.take(15).forEach { out.println("  " + ScreenText.nodeLine(it)) }
    }

    private suspend fun agentCommand(config: RunnerConfig): Int {
        config.missingModels()?.let {
            err.println(it)
            return 2
        }
        val channels = ConsoleChannels(input, out, interactive = hasConsole && !config.nonInteractive)
        val session = assemble(config, channels, llm, out)
        out.println("技能：${session.skillCount} 个；轨迹目录：${session.trace.dir.absolutePath}")
        if (!channels.interactive) out.println("非交互模式：需要确认的操作会被自动拒绝。")
        val listener = AgentListener { event ->
            session.trace.event(event)
            printEvent(event)
            if (event is AgentEvent.PhoneTaskFinished) session.sentinel.endTask(event.taskId)
        }
        if (config.command == "task") {
            stop.reset()
            return if (session.agent.runPhoneTask(config.goal!!, config.skill, listener, stop).success) 0 else 1
        }
        out.println("开始聊天，输入 /exit 退出；任务进行中按 Ctrl-C 停止当前任务。")
        while (true) {
            out.print("你 > ")
            out.flush()
            val line = input.readLine()?.trim() ?: break
            if (line == "/exit") break
            if (line.isEmpty()) continue
            stop.reset()
            session.agent.handle(line, listener, stop)
        }
        return 0
    }

    private fun printEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Reply -> out.println("PhonePal：${event.text}")
            is AgentEvent.SkillChosen -> out.println("使用技能：${event.skillName}")
            is AgentEvent.PhoneTaskStarted -> out.println("开始手机任务：${event.goal}" + (event.skillName?.let { "（技能 $it）" } ?: ""))
            is AgentEvent.StepPlanned -> out.println("  [${event.stepIndex + 1}] ${ScreenText.describe(event.action)}")
            is AgentEvent.StepExecuted -> if (!event.result.ok) out.println("      失败：${event.result.message ?: "无说明"}")
            is AgentEvent.StepBlocked -> out.println("      被安全模块拦下：${event.decision.reason}")
            is AgentEvent.NeedsUser -> out.println("  需要你：${event.question}")
            is AgentEvent.MemoryUpdated -> out.println("  已更新记忆：${event.permalink}")
            is AgentEvent.PhoneTaskFinished ->
                out.println((if (event.success) "任务完成" else "任务未完成") + "（${event.steps} 步）：${event.summary}")
            is AgentEvent.Failed -> out.println("出错：${event.message}")
        }
    }

    companion object {
        val USAGE = """
            ios-runner：在 Mac 上通过 WebDriverAgent 操作 iPhone 的 PhonePal。

            用法：
              ios-runner check [--wda URL]                     检查 WDA 连接、截图和界面节点
              ios-runner dump --out DIR [--wda URL]            保存截图、原始 source.xml 和节点列表
              ios-runner task "目标" [--skill 名称] [--max-steps N]   执行一个手机任务
              ios-runner chat                                  交互聊天，输入 /exit 退出

            通用参数：
              --wda URL           WDA 地址，默认 PHONEPAL_WDA_URL 或 http://127.0.0.1:8100
                                  不插线时用手机的局域网地址：scripts/ios/wda.sh url
              --home DIR          数据目录（memory/ skills/ traces/ config.properties），默认 PHONEPAL_HOME 或 ~/.phonepal-ios
              --skills DIR        追加技能目录，可重复，例如仓库里的 skills/ios
              --non-interactive   不读终端输入：需要确认的操作一律拒绝
              --max-steps N       单个任务的最大步数，默认 30

            任务进行中按 Ctrl-C 会在下一步前停止任务，再按一次退出程序。
            模型：config.properties 写 chat.baseUrl、chat.model、operator.baseUrl、operator.model；
            环境变量 PHONEPAL_CHAT_MODEL / PHONEPAL_OPERATOR_MODEL / PHONEPAL_CHAT_BASE_URL / PHONEPAL_OPERATOR_BASE_URL 可覆盖。
            API Key 只从环境变量读取：PHONEPAL_CHAT_API_KEY、PHONEPAL_OPERATOR_API_KEY，缺省用 ARK_API_KEY。
        """.trimIndent()
    }
}

/** First Ctrl-C stops the running task before its next step; a second one exits. */
class CtrlCStop : StopSignal {
    private val flag = AtomicBoolean(false)
    override val stopped: Boolean get() = flag.get()

    fun reset() = flag.set(false)

    /** Returns true when this press only stopped the task. */
    fun press(): Boolean = flag.compareAndSet(false, true)

    companion object {
        /** Hooks SIGINT where the JVM allows it; elsewhere Ctrl-C keeps its default (exit). */
        @Suppress("SunApi")
        fun install(): CtrlCStop {
            val stop = CtrlCStop()
            runCatching {
                sun.misc.Signal.handle(sun.misc.Signal("INT")) {
                    if (stop.press()) System.err.println("\n正在停止当前任务……再按一次 Ctrl-C 退出。") else exitProcess(130)
                }
            }
            return stop
        }
    }
}
