package dev.phonepal.ios.runner

import dev.phonepal.api.ModelConfig
import dev.phonepal.api.ModelRole
import java.io.File
import java.util.Properties

class UsageException(message: String) : Exception(message)

/** One model endpoint. The key comes from the environment only and never appears in [toString]. */
data class ModelSettings(val baseUrl: String, val model: String, val apiKey: String) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    fun toModelConfig(role: ModelRole) = ModelConfig(role, baseUrl, model, apiKey)

    override fun toString() = "ModelSettings(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"})"
}

/**
 * Command line, environment and `$PHONEPAL_HOME/config.properties`, merged in that order of precedence.
 * API keys are read from the environment only: `PHONEPAL_CHAT_API_KEY` / `PHONEPAL_OPERATOR_API_KEY`,
 * falling back to `ARK_API_KEY`.
 */
data class RunnerConfig(
    val command: String,
    /** Goal of `task`, question of `ask`, tool name of `call`. */
    val goal: String? = null,
    /** JSON arguments of `call`. */
    val toolArguments: String = "{}",
    val skill: String? = null,
    val outDir: File? = null,
    /** MCP servers file; `$PHONEPAL_HOME/mcp.json` unless `--mcp` is given. */
    val mcpFile: File? = null,
    val wdaUrl: String,
    val home: File,
    val extraSkillDirs: List<File> = emptyList(),
    val nonInteractive: Boolean = false,
    val maxSteps: Int = 30,
    /** `WDA_BUNDLE_ID` shared with scripts/ios/wda.sh; null means the script default. */
    val wdaBundleId: String? = null,
    val chat: ModelSettings,
    val operator: ModelSettings,
    val warnings: List<String> = emptyList(),
) {
    val memoryDir get() = File(home, "memory")
    val userSkillsDir get() = File(home, "skills")
    val tracesDir get() = File(home, "traces")
    val mcpConfigFile get() = mcpFile ?: File(home, "mcp.json")

    /** Extra skill folders first, the user's own skills last so they override. */
    val skillDirs get() = extraSkillDirs + userSkillsDir

    val secrets: List<String> get() = listOf(chat.apiKey, operator.apiKey).filter { it.isNotBlank() }.distinct()

    /** Chinese explanation of what is missing for task/chat, or null when both models are usable. */
    fun missingModels(): String? {
        val missing = buildList {
            if (chat.model.isBlank()) add("chat.model（或环境变量 PHONEPAL_CHAT_MODEL）")
            if (operator.model.isBlank()) add("operator.model（或环境变量 PHONEPAL_OPERATOR_MODEL）")
            if (chat.apiKey.isBlank()) add("环境变量 PHONEPAL_CHAT_API_KEY 或 ARK_API_KEY")
            if (operator.apiKey.isBlank()) add("环境变量 PHONEPAL_OPERATOR_API_KEY 或 ARK_API_KEY")
            if (chat.baseUrl.isBlank()) add("chat.baseUrl")
        }
        return missing.takeIf { it.isNotEmpty() }?.let {
            "模型还没配置好，缺少：" + it.joinToString("、") + "。模型名写在 ${File(home, "config.properties")}，Key 只用环境变量传入。"
        }
    }

    companion object {
        const val DEFAULT_WDA_URL = "http://127.0.0.1:8100"
        const val DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
        val COMMANDS = setOf("check", "dump", "task", "chat", "ask", "tools", "call")
        private val SECRET_KEY = Regex("key|secret|token|password", RegexOption.IGNORE_CASE)

        fun parse(args: List<String>, env: Map<String, String>, userHome: String = System.getProperty("user.home")): RunnerConfig {
            var command: String? = null
            val positional = mutableListOf<String>()
            var wda: String? = null
            var home: String? = null
            var skill: String? = null
            var out: String? = null
            var mcp: String? = null
            var maxSteps: Int? = null
            var nonInteractive = false
            val skillDirs = mutableListOf<File>()
            var i = 0
            fun value(name: String): String {
                if (i + 1 >= args.size) throw UsageException("$name 需要一个值")
                i++
                return args[i]
            }
            while (i < args.size) {
                when (val a = args[i]) {
                    "--wda" -> wda = value(a)
                    "--home" -> home = value(a)
                    "--skills" -> skillDirs += File(value(a))
                    "--skill" -> skill = value(a)
                    "--out" -> out = value(a)
                    "--mcp" -> mcp = value(a)
                    "--max-steps" -> maxSteps = value(a).toIntOrNull()?.takeIf { it in 1..200 }
                        ?: throw UsageException("--max-steps 需要 1..200 的整数")
                    "--non-interactive" -> nonInteractive = true
                    else -> when {
                        a.startsWith("--") -> throw UsageException("未知参数：$a")
                        command == null -> command = a
                        else -> positional += a
                    }
                }
                i++
            }
            val cmd = command ?: throw UsageException("缺少命令")
            if (cmd !in COMMANDS) throw UsageException("未知命令：$cmd")
            val goal = if (cmd == "task" || cmd == "ask") {
                positional.singleOrNull()?.takeIf { it.isNotBlank() }
                    ?: throw UsageException("$cmd 需要一段文字，例如 $cmd \"" + (if (cmd == "task") "打开设置里的无线局域网页面" else "明天北京天气怎么样") + "\"")
            } else if (cmd == "call") {
                if (positional.size !in 1..2) throw UsageException("call 需要工具名和可选的 JSON 参数，例如 call aihot__get_hot '{}'")
                positional[0]
            } else {
                if (positional.isNotEmpty()) throw UsageException("多余的参数：${positional.joinToString(" ")}")
                null
            }
            val toolArguments = if (cmd == "call") positional.getOrNull(1) ?: "{}" else "{}"
            if (cmd == "dump" && out == null) throw UsageException("dump 需要 --out DIR")

            val homeDir = File(home ?: env["PHONEPAL_HOME"]?.takeIf { it.isNotBlank() } ?: File(userHome, ".phonepal-ios").path)
            val warnings = mutableListOf<String>()
            val props = loadProperties(File(homeDir, "config.properties"), warnings)
            fun setting(envName: String, prop: String, default: String = "") =
                env[envName]?.takeIf { it.isNotBlank() } ?: props[prop] ?: default
            val chatBase = setting("PHONEPAL_CHAT_BASE_URL", "chat.baseUrl", DEFAULT_BASE_URL)
            val shared = env["ARK_API_KEY"].orEmpty()
            return RunnerConfig(
                command = cmd,
                goal = goal,
                toolArguments = toolArguments,
                skill = skill,
                outDir = out?.let(::File),
                mcpFile = mcp?.let(::File),
                wdaUrl = wda ?: env["PHONEPAL_WDA_URL"]?.takeIf { it.isNotBlank() } ?: DEFAULT_WDA_URL,
                home = homeDir,
                extraSkillDirs = skillDirs,
                nonInteractive = nonInteractive,
                maxSteps = maxSteps ?: 30,
                wdaBundleId = env["WDA_BUNDLE_ID"]?.takeIf { it.isNotBlank() },
                chat = ModelSettings(chatBase, setting("PHONEPAL_CHAT_MODEL", "chat.model"),
                    env["PHONEPAL_CHAT_API_KEY"]?.takeIf { it.isNotBlank() } ?: shared),
                operator = ModelSettings(setting("PHONEPAL_OPERATOR_BASE_URL", "operator.baseUrl", chatBase),
                    setting("PHONEPAL_OPERATOR_MODEL", "operator.model"),
                    env["PHONEPAL_OPERATOR_API_KEY"]?.takeIf { it.isNotBlank() } ?: shared),
                warnings = warnings,
            )
        }

        private fun loadProperties(file: File, warnings: MutableList<String>): Map<String, String> {
            if (!file.isFile) return emptyMap()
            val p = Properties()
            file.reader(Charsets.UTF_8).use(p::load)
            val result = mutableMapOf<String, String>()
            for (name in p.stringPropertyNames()) {
                if (SECRET_KEY.containsMatchIn(name)) {
                    warnings += "${file.name} 里的 $name 已忽略：Key 只从环境变量读取，请删掉文件里的这一行。"
                    continue
                }
                p.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { result[name] = it }
            }
            return result
        }
    }
}
