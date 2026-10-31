package dev.phonepal.agent

import dev.phonepal.api.AgentEvent
import dev.phonepal.api.MemoryStore
import dev.phonepal.api.Note
import dev.phonepal.api.Observation
import dev.phonepal.api.Relation
import dev.phonepal.api.Skill
import dev.phonepal.api.SkillRepository
import dev.phonepal.api.ToolCall
import dev.phonepal.api.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Tool definitions offered to the chat model. */
object AgentTools {
    const val MEMORY_SEARCH = "memory_search"
    const val MEMORY_READ = "memory_read"
    const val MEMORY_WRITE = "memory_write"
    const val MEMORY_FORGET = "memory_forget"
    const val LIST_SKILLS = "list_skills"
    const val PHONE_TASK = "phone_task"

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            MEMORY_SEARCH, "在用户的记忆笔记中全文搜索（支持中文）。",
            schema(listOf("query"), "query" to str("搜索词"), "limit" to int("最多返回条数，默认 5")),
        ),
        ToolSpec(
            MEMORY_READ, "读取一条记忆笔记的全文。",
            schema(listOf("identifier"), "identifier" to str("笔记的 permalink（如 places/家）或标题")),
        ),
        ToolSpec(
            MEMORY_WRITE,
            "新建或更新一条记忆笔记；已有同名笔记时追加观察和关系（相同内容不会重复）。",
            schema(
                listOf("title", "observations"),
                "title" to str("笔记标题，如 家"),
                "permalink" to str("可选，路径形式的唯一键，如 places/家；默认用标题"),
                "type" to str("可选，笔记类型，如 place、person、preference；默认 note"),
                "observations" to arr(
                    "观察条目",
                    schema(
                        listOf("category", "content"),
                        "category" to str("类别，如 address、preference"),
                        "content" to str("内容"),
                        "tags" to arr("标签，不带 #", str("标签")),
                        "context" to str("可选，来源或补充说明"),
                    ),
                ),
                "relations" to arr(
                    "可选，与其他笔记的关系",
                    schema(listOf("type", "target"), "type" to str("关系类型，如 lives_in"), "target" to str("目标笔记标题")),
                ),
            ),
        ),
        ToolSpec(
            MEMORY_FORGET, "删除一条记忆笔记（真正删除文件）。仅在用户明确要求忘记时使用。",
            schema(listOf("identifier"), "identifier" to str("笔记的 permalink 或标题")),
        ),
        ToolSpec(LIST_SKILLS, "列出所有可用的手机操作技能。", schema(emptyList())),
        ToolSpec(
            PHONE_TASK, "在用户的手机上完成一个操作任务，返回结果摘要。",
            schema(
                listOf("goal"),
                "goal" to str("具体目标，包含关键参数，如 用高德打车回家（北京市海淀区某某路 1 号），选快车"),
                "skill" to str("可选，使用的技能名"),
            ),
        ),
    )

    private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

    private fun str(description: String) = buildJsonObject { put("type", "string"); put("description", description) }
    private fun int(description: String) = buildJsonObject { put("type", "integer"); put("description", description) }
    private fun arr(description: String, items: JsonObject) = buildJsonObject {
        put("type", "array"); put("description", description); put("items", items)
    }
}

internal class ToolArgumentException(message: String) : Exception(message)

/** Runs the chat model's tool calls. Argument and runtime errors become `{"error": ...}` tool results. */
internal class ChatTools(
    private val memory: MemoryStore,
    private val skills: SkillRepository,
    private val clock: () -> Long,
    private val phoneTask: suspend (goal: String, skill: String?) -> AgentEvent.PhoneTaskFinished,
) {
    suspend fun execute(call: ToolCall, taskId: String, emit: (AgentEvent) -> Unit): String = try {
        val args = parseArgs(call.argumentsJson)
        when (call.name) {
            AgentTools.MEMORY_SEARCH -> search(args)
            AgentTools.MEMORY_READ -> read(args)
            AgentTools.MEMORY_WRITE -> write(args, taskId, emit)
            AgentTools.MEMORY_FORGET -> forget(args, taskId, emit)
            AgentTools.LIST_SKILLS -> listSkills()
            AgentTools.PHONE_TASK -> phone(args)
            else -> error("未知工具 ${call.name}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ToolArgumentException) {
        error("参数错误：${e.message}")
    } catch (e: Exception) {
        error("工具执行失败：${e.message ?: e::class.simpleName}")
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }.toString()

    private fun parseArgs(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return try {
            JsonText.json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw ToolArgumentException("arguments 不是合法的 JSON 对象")
    }

    private fun required(o: JsonObject, key: String): String =
        JsonText.string(o, key) ?: throw ToolArgumentException("缺少 $key")

    private suspend fun search(args: JsonObject): String {
        val query = required(args, "query")
        val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: 5).coerceIn(1, 20)
        val hits = memory.search(query, limit)
        return buildJsonObject {
            putJsonArray("hits") {
                hits.forEach { h -> addJsonObject { put("permalink", h.permalink); put("title", h.title); put("snippet", h.snippet) } }
            }
        }.toString()
    }

    private suspend fun read(args: JsonObject): String {
        val note = memory.read(required(args, "identifier")) ?: return buildJsonObject { put("found", false) }.toString()
        return buildJsonObject {
            put("found", true)
            put("title", note.title)
            put("permalink", note.permalink)
            put("type", note.type)
            putJsonArray("tags") { note.tags.forEach { add(it) } }
            putJsonArray("observations") { note.observations.forEach { add(NoteText.observationLine(it)) } }
            putJsonArray("relations") { note.relations.forEach { add(NoteText.relationLine(it)) } }
            put("body", note.body)
        }.toString()
    }

    private suspend fun write(args: JsonObject, taskId: String, emit: (AgentEvent) -> Unit): String {
        val title = required(args, "title")
        val observations = objects(args, "observations").map {
            Observation(required(it, "category"), required(it, "content"), JsonText.stringList(it, "tags"), JsonText.string(it, "context"))
        }
        val relations = objects(args, "relations").map {
            Relation(required(it, "type"), required(it, "target"), JsonText.string(it, "context"))
        }
        if (observations.isEmpty() && relations.isEmpty()) throw ToolArgumentException("observations 不能为空")

        val existing = memory.read(JsonText.string(args, "permalink") ?: title)
        val permalink = JsonText.string(args, "permalink") ?: existing?.permalink ?: title
        val mergedObs = ((existing?.observations ?: emptyList()) + observations).distinctBy { it.category to it.content }
        val mergedRels = ((existing?.relations ?: emptyList()) + relations).distinctBy { it.type to it.target }
        val note = Note(
            title = title,
            permalink = permalink,
            type = JsonText.string(args, "type") ?: existing?.type ?: "note",
            tags = existing?.tags ?: emptyList(),
            extraFrontmatter = existing?.extraFrontmatter ?: emptyMap(),
            body = NoteText.body(title, mergedObs, mergedRels, existing?.body?.let { NoteText.preamble(it) } ?: ""),
            observations = mergedObs,
            relations = mergedRels,
            updatedAtMillis = clock(),
        )
        val stored = memory.write(note)
        emit(AgentEvent.MemoryUpdated(taskId, stored.permalink))
        return buildJsonObject {
            put("ok", true)
            put("permalink", stored.permalink)
            put("created", existing == null)
            put("observations", mergedObs.size)
        }.toString()
    }

    private fun objects(args: JsonObject, key: String): List<JsonObject> = when (val e = args[key]) {
        null -> emptyList()
        is JsonArray -> e.map { it as? JsonObject ?: throw ToolArgumentException("$key 的每一项必须是对象") }
        is JsonObject -> listOf(e)
        else -> if (e is JsonPrimitive && e.content == "null") emptyList() else throw ToolArgumentException("$key 必须是数组")
    }

    private suspend fun forget(args: JsonObject, taskId: String, emit: (AgentEvent) -> Unit): String {
        val identifier = required(args, "identifier")
        val permalink = memory.read(identifier)?.permalink ?: identifier
        val deleted = memory.delete(identifier)
        if (deleted) emit(AgentEvent.MemoryUpdated(taskId, permalink))
        return buildJsonObject { put("deleted", deleted) }.toString()
    }

    private suspend fun listSkills(): String {
        val list = skills.list()
        return listSkillsJson(list)
    }

    private fun listSkillsJson(list: List<Skill>): String = buildJsonObject {
        putJsonArray("skills") {
            list.forEach { s ->
                addJsonObject {
                    put("name", s.name)
                    put("description", s.description)
                    put("apps", buildJsonArray { s.apps.forEach { add(it) } })
                    put("hasFlow", s.flow != null)
                }
            }
        }
    }.toString()

    private suspend fun phone(args: JsonObject): String {
        val finished = phoneTask(required(args, "goal"), JsonText.string(args, "skill"))
        return buildJsonObject {
            put("success", finished.success)
            put("summary", finished.summary)
            put("steps", finished.steps)
        }.toString()
    }
}
