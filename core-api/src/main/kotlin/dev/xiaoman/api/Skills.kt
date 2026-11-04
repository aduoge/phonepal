package dev.xiaoman.api

/**
 * A scenario skill written by users: a folder with `SKILL.md` and an optional `flow.yaml`.
 *
 * `SKILL.md` frontmatter:
 * ```
 * ---
 * name: taxi-home                 # required, [a-z0-9-], unique
 * description: 打车回家            # required, one line; shown to the chat model to pick skills
 * apps: [com.sdu.didi.psnger]     # package names the skill may operate
 * channel: deeplink-then-gui      # api | cli | deeplink | gui | deeplink-then-gui | mixed
 * confirm_before: [确认呼叫, 支付]  # words that force a user confirmation before tapping/typing on them
 * memory: [地址/家, 偏好/车型]       # notes to load into the operator prompt
 * deeplinks:                      # optional named deep links; {placeholders} filled by the operator
 *   search: "amapuri://route/plan/?dname={destination}"
 * ---
 * Markdown instructions for the operator model.
 * ```
 */
data class Skill(
    val name: String,
    val description: String,
    val apps: List<String> = emptyList(),
    val channel: SkillChannel = SkillChannel.GUI,
    val confirmBefore: List<String> = emptyList(),
    val memoryRefs: List<String> = emptyList(),
    val deeplinks: Map<String, String> = emptyMap(),
    /** Markdown body of SKILL.md after the frontmatter. */
    val instructions: String,
    val flow: Flow? = null,
    /** Where the skill was loaded from (folder path or URI), for display and reload. */
    val source: String,
)

enum class SkillChannel(val wire: String) {
    API("api"), CLI("cli"), DEEPLINK("deeplink"), GUI("gui"), DEEPLINK_THEN_GUI("deeplink-then-gui"), MIXED("mixed");

    companion object {
        fun fromWire(value: String): SkillChannel? = entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

/**
 * Optional `flow.yaml`: fixed steps run before or between free operator steps.
 * ```
 * steps:
 *   - launch: com.autonavi.minimap
 *   - deeplink: search            # a key of SKILL.md deeplinks, or a literal URI
 *   - ai: 选择快车并读出预估价       # a sub-goal handed to the operator loop
 *   - confirm: 确认呼叫快车？        # explicit user confirmation
 *   - tap: 确认呼叫                 # tap the element best matching this description
 *   - type: { target: 目的地, text: "{destination}" }
 *   - wait: 1500
 *   - assert: 行程已开始             # operator checks the screen shows this, else the flow fails
 * ```
 */
data class Flow(val steps: List<FlowStep>)

sealed class FlowStep {
    data class Launch(val packageName: String) : FlowStep()
    data class Deeplink(val keyOrUri: String) : FlowStep()
    data class Ai(val goal: String) : FlowStep()
    data class Confirm(val question: String) : FlowStep()
    data class Tap(val target: String) : FlowStep()
    data class Type(val target: String, val text: String) : FlowStep()
    data class Wait(val millis: Long) : FlowStep()
    data class Assert(val expectation: String) : FlowStep()
}

data class SkillProblem(val source: String, val message: String, val line: Int? = null)

/** Result of loading skills: problems never abort loading of the other skills. */
data class SkillLoadResult(val skills: List<Skill>, val problems: List<SkillProblem>)

interface SkillRepository {
    suspend fun reload(): SkillLoadResult

    suspend fun list(): List<Skill>

    suspend fun get(name: String): Skill?
}
