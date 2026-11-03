package dev.phonepal.web

import java.nio.file.Path
import java.time.ZoneId
import java.util.TimeZone

/** Sets the process timezone so core ReminderTimes parsing and ICS use the configured zone. */
fun main(args: Array<String>) {
    if ("--help" in args) {
        println("""PhonePal 网页版后端
用法：web-server [--host 127.0.0.1] [--port 8787] [--static DIR]
  [--access-code CODE] [--allow-private] [--timezone Asia/Shanghai] [--session-hours 2]
访问码也可通过 PHONEPAL_WEB_ACCESS_CODE 设置。默认仅监听本机。
--allow-private 同时允许模型和 MCP 访问内网，仅用于本机测试；--allow-private-mcp 为同义词。
将网页添加到 Safari 主屏幕；提醒可导入日历。""")
        return
    }
    try {
        var options = WebServer.Options(accessCode = System.getenv("PHONEPAL_WEB_ACCESS_CODE"))
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
                else -> error("参数无效")
            }
        }
        // Core reminder parsing uses the process timezone.
        TimeZone.setDefault(TimeZone.getTimeZone(options.timezone))
        val server = WebServer(options).start()
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })
        println("PhonePal 已监听 http://${options.host}:${server.port}")
    } catch (_: Exception) {
        System.err.println("启动失败，请检查参数和端口；使用 --help 查看用法")
        kotlin.system.exitProcess(1)
    }
}
