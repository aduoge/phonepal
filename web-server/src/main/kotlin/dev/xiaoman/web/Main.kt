package dev.xiaoman.web

import java.nio.file.Path
import java.time.ZoneId
import java.util.TimeZone

/** Sets the process timezone so core ReminderTimes parsing and ICS use the configured zone. */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "tokens") kotlin.system.exitProcess(ClassTokenAdmin.run(args.drop(1)))
    if ("--help" in args) {
        println("""小满网页版后端
用法：web-server [--host 127.0.0.1] [--port 8787] [--static DIR]
  [--access-code CODE] [--allow-private] [--timezone Asia/Shanghai] [--session-hours 2]
  [--stdio-mcp auto|on|off] [--max-stdio 20] [--class-tokens FILE]
       web-server tokens …   管理课堂令牌（web-server tokens --help）
访问码也可通过 XIAOMAN_WEB_ACCESS_CODE 设置。默认仅监听本机。
--stdio-mcp 控制需要 Node.js 的目录服务（12306 火车票）：auto（默认）在 PATH 里找到 node 和 npx 时开启，
  on 强制开启，off 关闭；每个会话一个子进程，最多 --max-stdio 个同时运行。
记忆保存在学员浏览器里，随每次对话发来，服务器不落盘。
--allow-private 同时允许模型和 MCP 访问内网，仅用于本机测试；--allow-private-mcp 为同义词。
将网页添加到 Safari 主屏幕；提醒可导入日历。
课堂令牌（默认关闭）：设置 XIAOMAN_CLASS_UPSTREAM_KEY_FILE（或 XIAOMAN_CLASS_UPSTREAM_KEY）和
  XIAOMAN_CLASS_MODELS（如 xiaoman-class=doubao-seed-1-6-250615），并用 web-server tokens generate 生成令牌文件后开启；
  可选 XIAOMAN_CLASS_UPSTREAM_URL、XIAOMAN_CLASS_TOKENS、XIAOMAN_CLASS_WEB_MODEL、XIAOMAN_CLASS_TOKEN_RPM、
  XIAOMAN_CLASS_TOKEN_CONCURRENCY、XIAOMAN_CLASS_MAX_CONCURRENT、XIAOMAN_CLASS_MAX_BODY_MB、XIAOMAN_CLASS_TIMEOUT_SECONDS。""")
        return
    }
    try {
        var options = WebServer.Options(accessCode = System.getenv("XIAOMAN_WEB_ACCESS_CODE"))
        var stdioMode = "auto"
        var classTokens: Path? = null
        var i = 0
        while (i < args.size) {
            val flag = args[i++]
            fun value(): String { require(i < args.size); return args[i++] }
            options = when (flag) {
                "--host" -> options.copy(host = value())
                "--port" -> options.copy(port = value().toInt().also { require(it in 0..65535) })
                "--static" -> options.copy(staticRoot = Path.of(value()))
                "--access-code" -> options.copy(accessCode = value())
                "--allow-private", "--allow-private-mcp" -> options.copy(allowPrivateMcp = true)
                "--timezone" -> options.copy(timezone = ZoneId.of(value()))
                "--session-hours" -> options.copy(sessionMillis = (value().toDouble() * 3_600_000).toLong())
                "--stdio-mcp" -> { stdioMode = value().also { require(it in setOf("auto", "on", "off")) }; options }
                "--max-stdio" -> options.copy(maxStdioProcesses = value().toInt())
                "--class-tokens" -> { classTokens = Path.of(value()); options }
                else -> error("参数无效")
            }
        }
        val nodeFound = StdioMcp.commandsOnPath()
        options = options.copy(stdioMcp = when (stdioMode) { "on" -> true; "off" -> false; else -> nodeFound })
        val (classConfig, classStatus) = try { ClassConfig.fromEnv(System.getenv(), classTokens) } catch (e: IllegalArgumentException) {
            System.err.println("课堂令牌配置无效：${e.message}"); throw e
        } catch (e: IllegalStateException) { System.err.println("课堂令牌配置无效：${e.message}"); throw e }
        options = options.copy(classConfig = classConfig)
        // Core reminder parsing uses the process timezone.
        TimeZone.setDefault(TimeZone.getTimeZone(options.timezone))
        val server = WebServer(options).start()
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        println("小满已监听 http://${options.host}:${server.port}")
        println(when {
            options.stdioMcp && !nodeFound -> "12306 火车票（stdio）：已强制开启，但 PATH 里没有 node/npx，调用会失败"
            options.stdioMcp -> "12306 火车票（stdio）：已开启，最多 ${options.maxStdioProcesses} 个进程"
            stdioMode == "off" -> "12306 火车票（stdio）：已关闭（--stdio-mcp off），网页上显示为不可用"
            else -> "12306 火车票（stdio）：未找到 node/npx，网页上显示为不可用"
        })
        println(if (classConfig == null) "课堂令牌：未开启（$classStatus）" else {
            val tokens = ClassTokenStore(classConfig.tokensFile, options.timezone).takeIf { it.exists() }?.all()
            "课堂令牌：上游 ${classConfig.upstreamUrl}，模型 ${classConfig.models.keys.joinToString("、")}，" +
                (tokens?.let { "令牌文件 ${classConfig.tokensFile}（${it.size} 个）" } ?: "令牌文件 ${classConfig.tokensFile} 还不存在，生成令牌后自动开启")
        })
    } catch (_: Exception) {
        System.err.println("启动失败，请检查参数和端口；使用 --help 查看用法")
        kotlin.system.exitProcess(1)
    }
}
