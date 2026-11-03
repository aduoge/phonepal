package dev.phonepal.ios.runner

import dev.phonepal.agent.PhonePalAgent
import dev.phonepal.api.AgentConfig
import dev.phonepal.api.ModelRole
import dev.phonepal.ios.IosPlatform
import dev.phonepal.ios.WdaClient
import dev.phonepal.ios.WdaDevice
import dev.phonepal.llm.LlmClientFactory
import dev.phonepal.mcp.McpConfig
import dev.phonepal.mcp.McpToolProvider
import dev.phonepal.memory.FileMemoryStore
import dev.phonepal.sentinel.PolicySentinel
import dev.phonepal.skills.DirectorySkillRepository
import java.io.PrintStream

/** The assembled agent with the pieces the command line needs to reach. */
class RunnerSession(
    val agent: PhonePalAgent,
    val sentinel: PolicySentinel,
    val trace: TraceRecorder,
    val skillCount: Int,
    /** MCP tools offered to the chat model, null when `mcp.json` configures no usable server. */
    val mcp: McpToolProvider?,
)

/** MCP servers from [RunnerConfig.mcpConfigFile]; warnings never contain the substituted keys. */
fun loadMcp(config: RunnerConfig, env: Map<String, String>, approvals: ConsoleChannels, out: PrintStream): McpToolProvider? {
    val mcp = McpConfig.load(config.mcpConfigFile, env)
    mcp.warnings.forEach { out.println("MCP：$it") }
    return mcp.takeIf { c -> c.servers.any { it.enabled } }?.let { McpToolProvider(it, approvals) }
}

/** Wires the core modules to a WDA-backed iPhone, as AppGraph does for the Android app. */
suspend fun assemble(
    config: RunnerConfig,
    channels: ConsoleChannels,
    llm: LlmClientFactory,
    out: PrintStream,
    env: Map<String, String> = System.getenv(),
): RunnerSession {
    config.memoryDir.mkdirs()
    config.userSkillsDir.mkdirs()
    val trace = TraceRecorder(config.tracesDir, config.secrets)
    val skills = DirectorySkillRepository(config.skillDirs)
    val loaded = skills.reload()
    loaded.problems.forEach { out.println("技能问题：${it.source}${it.line?.let { l -> ":$l" } ?: ""} ${it.message}") }
    val sentinel = PolicySentinel(
        approvals = channels,
        audit = trace,
        launcherPackages = IosPlatform.LAUNCHER_BUNDLES,
        selfPackage = IosPlatform.runnerBundleId(config.wdaBundleId),
    )
    val mcp = loadMcp(config, env, channels, out)
    val agent = PhonePalAgent(
        chat = llm.create(config.chat.toModelConfig(ModelRole.CHAT)),
        operator = llm.create(config.operator.toModelConfig(ModelRole.OPERATOR)),
        device = trace.device(WdaDevice(WdaClient(config.wdaUrl))),
        memory = FileMemoryStore(config.memoryDir),
        skills = skills,
        sentinel = sentinel,
        users = channels,
        reminders = MacReminders(out),
        config = AgentConfig(maxSteps = config.maxSteps, operatorNotes = IosPlatform.OPERATOR_NOTES),
        externalTools = mcp,
    )
    return RunnerSession(agent, sentinel, trace, loaded.skills.size, mcp)
}
