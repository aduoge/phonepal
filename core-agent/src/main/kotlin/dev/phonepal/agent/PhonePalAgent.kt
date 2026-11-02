package dev.phonepal.agent

import dev.phonepal.api.Agent
import dev.phonepal.api.AgentConfig
import dev.phonepal.api.AgentEvent
import dev.phonepal.api.AgentListener
import dev.phonepal.api.ChatMessage
import dev.phonepal.api.ChatRequest
import dev.phonepal.api.ChatRole
import dev.phonepal.api.ContentPart
import dev.phonepal.api.Device
import dev.phonepal.api.LlmClient
import dev.phonepal.api.LlmException
import dev.phonepal.api.MemoryStore
import dev.phonepal.api.Note
import dev.phonepal.api.Observation
import dev.phonepal.api.ReminderScheduler
import dev.phonepal.api.Sentinel
import dev.phonepal.api.Skill
import dev.phonepal.api.SkillRepository
import dev.phonepal.api.StopSignal
import dev.phonepal.api.UserChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The PhonePal agent: chat turns with memory and phone tools ([handle]), and phone tasks run by
 * the operator loop or a skill flow ([runPhoneTask]). See docs/ARCHITECTURE.md.
 *
 * Conversation history is kept in memory, bounded to the last [HISTORY_LIMIT] user/assistant messages.
 */
class PhonePalAgent(
    private val chat: LlmClient,
    operator: LlmClient,
    device: Device,
    private val memory: MemoryStore,
    private val skills: SkillRepository,
    sentinel: Sentinel,
    users: UserChannel,
    private val reminders: ReminderScheduler,
    private val config: AgentConfig = AgentConfig(),
    private val persona: String = Prompts.DEFAULT_PERSONA,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : Agent {
    private val loop = OperatorLoop(operator, device, sentinel, users, config)
    private val flows = FlowRunner(loop, users)
    private val historyLock = Mutex()
    private val history = ArrayDeque<ChatMessage>()

    override suspend fun handle(userMessage: String, listener: AgentListener, stop: StopSignal) {
        val taskId = idFactory()
        val emit: (AgentEvent) -> Unit = listener::onEvent
        val tools = ChatTools(memory, skills, clock, reminders) { goal, skill -> runPhoneTask(goal, skill, listener, stop) }
        try {
            val system = ChatMessage.system(systemPrompt(userMessage))
            val past = historyLock.withLock { history.toList() }
            val turn = mutableListOf(ChatMessage.user(userMessage))
            var lastText: String? = null
            var finalText: String? = null
            for (round in 0..MAX_TOOL_ROUNDS) {
                val offerTools = round < MAX_TOOL_ROUNDS
                val response = chat.complete(
                    ChatRequest(listOf(system) + past + turn, tools = if (offerTools) AgentTools.specs else emptyList()),
                )
                val text = response.text.trim()
                if (response.toolCalls.isEmpty() || !offerTools) {
                    finalText = text.ifEmpty { if (response.toolCalls.isNotEmpty()) Prompts.TOOL_ROUNDS_EXCEEDED else "" }
                    break
                }
                if (text.isNotEmpty()) {
                    emit(AgentEvent.Reply(taskId, text))
                    lastText = text
                }
                turn += ChatMessage(ChatRole.ASSISTANT, listOf(ContentPart.Text(response.text)), toolCalls = response.toolCalls)
                for (call in response.toolCalls) {
                    turn += ChatMessage.tool(call.id, tools.execute(call, taskId, emit))
                }
                if (stop.stopped) break
            }
            if (!finalText.isNullOrEmpty()) emit(AgentEvent.Reply(taskId, finalText))
            remember(userMessage, finalText?.takeIf { it.isNotEmpty() } ?: lastText)
        } catch (e: LlmException) {
            emit(AgentEvent.Failed(taskId, Prompts.llmFailed(e)))
        }
    }

    override suspend fun runPhoneTask(goal: String, skillName: String?, listener: AgentListener, stop: StopSignal): AgentEvent.PhoneTaskFinished {
        val taskId = idFactory()
        fun finish(success: Boolean, summary: String, steps: Int) =
            AgentEvent.PhoneTaskFinished(taskId, success, summary, steps).also(listener::onEvent)

        val wanted = skillName?.trim()?.takeIf { it.isNotEmpty() }
        val skill = wanted?.let { quietly(null) { skills.get(it) } }
        if (skill != null) listener.onEvent(AgentEvent.SkillChosen(taskId, skill.name))
        listener.onEvent(AgentEvent.PhoneTaskStarted(taskId, goal, skill?.name))
        if (wanted != null && skill == null) return finish(false, "${Prompts.SKILL_NOT_FOUND}：$wanted", 0)

        val task = TaskRun(taskId, goal, skill, listener, stop)
        val outcome = try {
            task.references = references(skill)
            task.howto = howto(goal, skill)
            val flow = skill?.flow
            if (flow != null && flow.steps.isNotEmpty()) flows.run(task, flow) else loop.run(task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            val message = Prompts.operatorLlmFailed(e)
            listener.onEvent(AgentEvent.Failed(taskId, message))
            LoopOutcome(false, message)
        } catch (e: Exception) {
            // e.g. the accessibility service is not connected: device.observe() throws.
            val message = e.message?.takeIf { it.isNotBlank() } ?: Prompts.UNEXPECTED_ERROR
            listener.onEvent(AgentEvent.Failed(taskId, message))
            LoopOutcome(false, message)
        }
        if (outcome.success && config.learnFromSuccess) quietly(Unit) { learn(task) }
        return finish(outcome.success, outcome.summary, task.steps)
    }

    private suspend fun systemPrompt(userMessage: String): String = buildString {
        append(persona.trim())
        append("\n\n").append(Prompts.nowSection(ReminderTimes.promptNow(clock())))
        append("\n\n").append(Prompts.SKILLS_HEADER).append('\n')
        val list = quietly(emptyList()) { skills.list() }
        if (list.isEmpty()) append(Prompts.NO_SKILLS) else list.forEach { append("- ").append(it.name).append(": ").append(it.description).append('\n') }
        val hits = quietly(emptyList()) { memory.search(userMessage, MEMORY_HITS) }
        if (hits.isNotEmpty()) {
            append("\n\n").append(Prompts.MEMORY_HEADER).append('\n')
            hits.forEach { append("- ").append(it.title).append(" (").append(it.permalink).append("): ").append(it.snippet.replace('\n', ' ')).append('\n') }
        }
    }.trimEnd()

    private suspend fun remember(userMessage: String, reply: String?) = historyLock.withLock {
        history.addLast(ChatMessage.user(userMessage))
        if (!reply.isNullOrBlank()) history.addLast(ChatMessage.assistant(reply))
        while (history.size > HISTORY_LIMIT) history.removeFirst()
        // Never start the window with an assistant message.
        while (history.firstOrNull()?.role == ChatRole.ASSISTANT) history.removeFirst()
    }

    private suspend fun references(skill: Skill?): String {
        if (skill == null) return ""
        return skill.memoryRefs.mapNotNull { ref -> quietly(null) { memory.read(ref) }?.let { NoteText.forPrompt(it) } }.joinToString("\n\n")
    }

    /** `howto` notes for the goal: the skill's own note first, then search hits of type howto. */
    private suspend fun howto(goal: String, skill: Skill?): String {
        val notes = LinkedHashMap<String, Note>()
        skill?.let { s -> quietly(null) { memory.read(howtoPermalink(s.name)) }?.let { notes[it.permalink] = it } }
        for (hit in quietly(emptyList()) { memory.search(goal, MEMORY_HITS) }) {
            if (notes.size >= MAX_HOWTO_NOTES) break
            if (hit.permalink in notes) continue
            quietly(null) { memory.read(hit.permalink) }?.takeIf { it.type == HOWTO_TYPE }?.let { notes[it.permalink] = it }
        }
        return notes.values.take(MAX_HOWTO_NOTES).joinToString("\n\n") { NoteText.forPrompt(it, maxChars = 800) }
    }

    /** Records the successful path as `- [path] a → b → c #skill` in `howto/<skill-or-app>`. */
    private suspend fun learn(task: TaskRun) {
        if (task.path.isEmpty()) return
        val key = task.skill?.name ?: task.launchedPackage ?: task.lastPackage ?: return
        val permalink = howtoPermalink(key)
        val observation = Observation(PATH_CATEGORY, task.path.joinToString(" → "), tags = listOf(key))
        val existing = memory.read(permalink)
        if (existing != null) {
            if (existing.observations.any { it.category == observation.category && it.content == observation.content }) return
            memory.appendObservation(permalink, observation)
        } else {
            val title = "$key 操作路径"
            memory.write(
                Note(
                    title = title,
                    permalink = permalink,
                    type = HOWTO_TYPE,
                    tags = listOf(HOWTO_TYPE),
                    body = NoteText.body(title, listOf(observation), emptyList()),
                    observations = listOf(observation),
                    updatedAtMillis = clock(),
                ),
            )
        }
        task.emit(AgentEvent.MemoryUpdated(task.taskId, permalink))
    }

    private suspend fun <T> quietly(default: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        default
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 8
        const val HISTORY_LIMIT = 20
        const val MEMORY_HITS = 5
        const val HOWTO_TYPE = "howto"
        const val PATH_CATEGORY = "path"
        private const val MAX_HOWTO_NOTES = 3

        fun howtoPermalink(key: String) = "howto/$key"
    }
}
