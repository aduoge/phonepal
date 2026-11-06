package dev.xiaoman.web

import dev.xiaoman.agent.*
import dev.xiaoman.api.*
import dev.xiaoman.llm.LlmClientFactory
import dev.xiaoman.mcp.*
import dev.xiaoman.sentinel.PolicySentinel
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Closeable
import java.security.MessageDigest
import java.time.ZoneId

class WebSession(val id: String, var lastUsed: Long, clock: () -> Long) : Closeable {
    val memory = InMemoryMemory(clock)
    val channels = WebChannels()
    var busy = false
    @Volatile var job: Job? = null
    var agent: XiaomanAgent? = null
    private var provider: McpToolProvider? = null
    private var hash: String? = null
    private val secrets = linkedSetOf<String>()
    var closed = false
        private set

    fun rememberSecrets(body: JsonObject) {
        val model = body.obj("model")
        val mcp = body.obj("mcp")
        val values = mutableListOf(model.str("apiKey"), model.str("classToken"))
        values += mcp.obj("keys").values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        mcp.arr("custom").forEach { item ->
            val o = item.jsonObject
            values += o.obj("headers").values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            o.str("url").toHttpUrlOrNull()?.let { u ->
                values += u.password
                u.queryParameterNames.forEach { values += u.queryParameterValues(it).filterNotNull() }
            }
        }
        values.toList().forEach { if (it.startsWith("Bearer ", true)) values += it.substring(7) }
        synchronized(secrets) { secrets += values.filter { it.length >= 6 }.flatMap { listOf(it, JsonPrimitive(it).toString().removeSurrounding("\"")) } }
    }
    fun rememberSecret(value: String) { if (value.length >= 6) synchronized(secrets) { secrets += value } }
    fun redact(value: JsonElement): JsonElement {
        fun text(s: String) = synchronized(secrets) { secrets.sortedByDescending { it.length }.fold(s) { r, key -> r.replace(key, "***") } }
        return when (value) {
            is JsonPrimitive -> if (value.isString) JsonPrimitive(text(value.content)) else value
            is JsonArray -> JsonArray(value.map(::redact))
            is JsonObject -> JsonObject(value.entries.associate { text(it.key) to redact(it.value) })
        }
    }
    @Synchronized fun assemble(body: JsonObject, config: McpConfig, llm: LlmClientFactory,
        transports: (McpServerConfig) -> McpTransport, timezone: ZoneId, clock: () -> Long): XiaomanAgent {
        check(!closed)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(
            buildJsonObject { put("model", body.obj("model")); put("mcp", body.obj("mcp")) }.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (hash == fingerprint) return requireNotNull(agent)
        provider?.close()
        provider = null
        val m = body.obj("model")
        val chat = llm.create(ModelConfig(ModelRole.CHAT, m.str("baseUrl"), m.str("model"), m.str("apiKey")))
        val operator = llm.create(chat.config.copy(role = ModelRole.OPERATOR))
        val p = McpToolProvider(config, channels, transportFactory = transports, clock = clock)
        provider = p
        return XiaomanAgent(chat, operator, NoPhone, memory, EmptySkills,
            PolicySentinel(approvals = { ApprovalAnswer(false) }, audit = {}), channels,
            IcsReminders(channels, timezone, clock), AgentConfig(phoneTasks = false), Prompts.NO_PHONE_PERSONA,
            clock = clock, externalTools = p).also { agent = it; hash = fingerprint }
    }
    @Synchronized override fun close() {
        closed = true; job?.cancel(); channels.detach(); provider?.close(); provider = null
        agent = null; hash = null; memory.clear()
    }
}
internal fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.arr(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private object NoPhone : Device {
    override suspend fun observe(): ScreenState = error("网页版不能操作手机")
    override suspend fun perform(action: Action) = ActionResult(false, "网页版不能操作手机")
    override suspend fun installedApps() = emptyList<AppInfo>()
}
private object EmptySkills : SkillRepository {
    override suspend fun reload() = SkillLoadResult(emptyList(), emptyList())
    override suspend fun list() = emptyList<Skill>()
    override suspend fun get(name: String): Skill? = null
}
