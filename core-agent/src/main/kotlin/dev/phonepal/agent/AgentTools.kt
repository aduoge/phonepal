package dev.phonepal.agent

import dev.phonepal.api.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
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
    const val REMINDER_SET = "reminder_set"
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
            REMINDER_SET,
            "设置一条定时提醒：到点后 PhonePal 会在手机上弹出通知。",
            schema(
                listOf("text", "at"),
                "text" to str("提醒内容，如 喝水、带伞"),
                "at" to str("触发时间，ISO-8601 本地时间（设备时区，不带时区后缀），如 2026-11-03T08:00"),
            ),
        ),
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
