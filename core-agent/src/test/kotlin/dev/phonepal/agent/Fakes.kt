package dev.phonepal.agent

import dev.phonepal.api.Action
import dev.phonepal.api.ActionResult
import dev.phonepal.api.AgentConfig
import dev.phonepal.api.AgentEvent
import dev.phonepal.api.AgentListener
import dev.phonepal.api.AppInfo
import dev.phonepal.api.Bounds
import dev.phonepal.api.ChatRequest
import dev.phonepal.api.ChatResponse
import dev.phonepal.api.Decision
import dev.phonepal.api.Device
import dev.phonepal.api.GuardContext
import dev.phonepal.api.LlmClient
import dev.phonepal.api.LlmException
import dev.phonepal.api.MemoryStore
import dev.phonepal.api.ModelConfig
import dev.phonepal.api.ModelRole
import dev.phonepal.api.Note
import dev.phonepal.api.Observation
import dev.phonepal.api.ReminderScheduler
import dev.phonepal.api.ScreenState
import dev.phonepal.api.SearchHit
import dev.phonepal.api.Sentinel
import dev.phonepal.api.Skill
import dev.phonepal.api.SkillLoadResult
import dev.phonepal.api.SkillRepository
import dev.phonepal.api.StopSignal
import dev.phonepal.api.ToolCall
import dev.phonepal.api.UiNode
import dev.phonepal.api.UserChannel

/** LLM returning scripted responses in order; [fallback] answers once the script is used up. */
class ScriptedLlm(role: ModelRole = ModelRole.CHAT, var fallback: ((ChatRequest) -> ChatResponse)? = null) : LlmClient {
    override val config = ModelConfig(role, "http://fake", "fake-model", "secret")
    val requests = mutableListOf<ChatRequest>()
    private val script = ArrayDeque<(ChatRequest) -> ChatResponse>()

    fun reply(text: String) = apply { script.addLast { ChatResponse(text) } }
    fun tools(vararg calls: ToolCall) = apply { script.addLast { ChatResponse("", calls.toList()) } }
    fun fail(message: String = "boom") = apply { script.addLast { throw LlmException(message, 503, retryable = true) } }
    fun respond(block: (ChatRequest) -> ChatResponse) = apply { script.addLast(block) }

    override suspend fun complete(request: ChatRequest): ChatResponse {
        requests += request
        val next = script.removeFirstOrNull() ?: fallback ?: error("no scripted response left")
        return next(request)
    }
}

/** Device whose observe() returns [screens] in order (the last one repeats). */
class FakeDevice(vararg screens: ScreenState) : Device {
    private val queue = ArrayDeque(screens.toList())
    var observeError: Exception? = null
    val performed = mutableListOf<Action>()
    var onPerform: (Action) -> Unit = {}
    var observations = 0

    override suspend fun observe(): ScreenState {
        observeError?.let { throw it }
        observations++
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    override suspend fun perform(action: Action): ActionResult {
        performed += action
        onPerform(action)
        return ActionResult(true)
    }

    override suspend fun installedApps() = listOf(AppInfo("com.android.settings", "设置"))
}

fun screen(pkg: String, vararg texts: String, secure: Boolean = false) = ScreenState(
    screenshotPng = if (secure) null else byteArrayOf(1, 2, 3),
    widthPx = 1080,
    heightPx = 2400,
    packageName = pkg,
    nodes = texts.mapIndexed { i, t -> UiNode(id = i, text = t, className = "android.widget.TextView", bounds = Bounds(0, i * 100, 1000, i * 100 + 90), clickable = true) },
    secure = secure,
)

class InMemoryMemory : MemoryStore {
    val notes = LinkedHashMap<String, Note>()

    override suspend fun write(note: Note): Note = note.also { notes[it.permalink] = it }
    override suspend fun read(identifier: String): Note? = notes[identifier] ?: notes.values.firstOrNull { it.title == identifier }

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val grams = query.windowed(2).toSet().ifEmpty { setOf(query) }
        return notes.values.mapNotNull { n ->
            val text = n.title + "\n" + n.body + n.observations.joinToString("\n") { it.content }
            val score = grams.count { it in text }
            if (score > 0) SearchHit(n.permalink, n.title, n.observations.firstOrNull()?.content ?: n.body.take(40), score.toDouble()) else null
        }.sortedByDescending { it.score }.take(limit)
    }

    override suspend fun appendObservation(identifier: String, observation: Observation): Note {
        val n = read(identifier) ?: Note(title = identifier, permalink = identifier)
        return write(n.copy(observations = n.observations + observation))
    }

    override suspend fun delete(identifier: String): Boolean = read(identifier)?.let { notes.remove(it.permalink) } != null
    override suspend fun list(folder: String?): List<Note> = notes.values.filter { folder == null || it.permalink.startsWith("$folder/") }
}

class FakeSkills(vararg skills: Skill) : SkillRepository {
    private val all = skills.toList()
    override suspend fun reload() = SkillLoadResult(all, emptyList())
    override suspend fun list() = all
    override suspend fun get(name: String) = all.firstOrNull { it.name == name }
}

fun skill(name: String, description: String = name, flow: dev.phonepal.api.Flow? = null, deeplinks: Map<String, String> = emptyMap(), memoryRefs: List<String> = emptyList()) =
    Skill(name = name, description = description, apps = listOf("com.example.app"), deeplinks = deeplinks, memoryRefs = memoryRefs, instructions = "按步骤操作 $name", flow = flow, source = "test")

/** Denies whatever [policy] returns a non-Allow decision for. */
class FakeSentinel(private val policy: (Action) -> Decision = { Decision.Allow() }) : Sentinel {
    val authorized = mutableListOf<Action>()
    override suspend fun guard(context: GuardContext, action: Action) = policy(action)
    override suspend fun authorize(context: GuardContext, action: Action): Boolean {
        authorized += action
        return guard(context, action) is Decision.Allow
    }
}

class FakeUsers(vararg answers: String) : UserChannel {
    private val queue = ArrayDeque(answers.toList())
    val asked = mutableListOf<Pair<String, Boolean>>()
    override suspend fun ask(taskId: String, question: String, takeover: Boolean): String {
        asked += question to takeover
        return queue.removeFirstOrNull() ?: "好"
    }
}

class FakeReminderScheduler : ReminderScheduler {
    val scheduled = mutableListOf<Pair<String, Long>>()
    var failure: Exception? = null
    override fun schedule(text: String, atMillis: Long) {
        failure?.let { throw it }
        scheduled += text to atMillis
    }
}

class Events : AgentListener {
    val all = mutableListOf<AgentEvent>()
    override fun onEvent(event: AgentEvent) {
        all += event
    }
    inline fun <reified T : AgentEvent> of(): List<T> = all.filterIsInstance<T>()
}

class Stop(var value: Boolean = false) : StopSignal {
    override val stopped get() = value
}

/** Wires a [PhonePalAgent] with fakes; tests tweak the parts they care about. */
class Harness(
    val chat: ScriptedLlm = ScriptedLlm(ModelRole.CHAT),
    val operator: ScriptedLlm = ScriptedLlm(ModelRole.OPERATOR),
    val device: FakeDevice = FakeDevice(screen("com.android.launcher", "设置")),
    val memory: InMemoryMemory = InMemoryMemory(),
    val skills: FakeSkills = FakeSkills(),
    val sentinel: FakeSentinel = FakeSentinel(),
    val users: FakeUsers = FakeUsers(),
    val reminders: FakeReminderScheduler = FakeReminderScheduler(),
    val config: AgentConfig = AgentConfig(),
    val clock: () -> Long = { 1000L },
) {
    private var ids = 0
    val agent = PhonePalAgent(chat, operator, device, memory, skills, sentinel, users, reminders, config, clock = clock, idFactory = { "t${++ids}" })
    val events = Events()
    val stop = Stop()
}

/** Operator reply JSON for an action object. */
fun op(action: String, thought: String = "想一想") = "{\"thought\": \"$thought\", \"action\": $action}"
