package dev.xiaoman.sentinel

import dev.xiaoman.api.Action
import dev.xiaoman.api.ApprovalGateway
import dev.xiaoman.api.ApprovalRequest
import dev.xiaoman.api.ApprovalScope
import dev.xiaoman.api.AuditRecord
import dev.xiaoman.api.AuditSink
import dev.xiaoman.api.Decision
import dev.xiaoman.api.GuardContext
import dev.xiaoman.api.Point
import dev.xiaoman.api.ScreenState
import dev.xiaoman.api.Sentinel
import dev.xiaoman.api.Skill
import dev.xiaoman.api.UiNode
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * The rule-based [Sentinel] described in docs/ARCHITECTURE.md (Safety).
 *
 * Rules, first match wins:
 * 1. deny
 *    - any device action on a secure (FLAG_SECURE) screen except `back`, `home` and `wait`; the user
 *      must take over. `ask_user` and `finish` never touch the device and stay allowed;
 *    - `type` into a password node ([Action.TypeText.nodeId], else the focused editable node);
 *    - when the skill lists `apps`: `launch` / `deeplink` naming a package outside them, and `tap`,
 *      `long_press`, `type` while the foreground package is outside them. Launchers and
 *      [selfPackage] are always permitted. System UI (status bar, permission dialogs) is not
 *      exempt: there only `back`/`home` (and swipes) go through.
 * 2. ask
 *    - `tap`, `long_press`, `type` whose target, or the acted-on node's text or description,
 *      contains a confirmation word: the built-in list, plus the skill's `confirm_before`, plus
 *      [extraConfirmWords]. Neither source can remove a built-in word. The node is
 *      resolved by id, or for points as the smallest node containing the point; for `type`
 *      without an id it is the focused editable node;
 *    - `deeplink` whose URI matches none of the skill's `deeplinks` templates by scheme and host
 *      (always, when there is no skill or the skill has no templates).
 * 3. allow everything else.
 *
 * [authorize] remembers TASK-scoped approvals per task id, keyed by the matched confirmation word
 * (or the deeplink's scheme and host) until [endTask]. Every decision goes to [audit]. Thread-safe.
 */
class PolicySentinel(
    private val approvals: ApprovalGateway,
    private val audit: AuditSink,
    launcherPackages: Set<String> = DEFAULT_LAUNCHERS,
    private val selfPackage: String = "dev.xiaoman.app",
    extraConfirmWords: List<String> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Sentinel {

    private val launcherPackages: Set<String> = launcherPackages.toSet()
    private val baseWords: List<String> = ConfirmWords.normalize(ConfirmWords.BUILT_IN + extraConfirmWords)

    /** taskId -> approval keys granted with [ApprovalScope.TASK]. */
    private val taskApprovals = ConcurrentHashMap<String, MutableSet<String>>()

    override suspend fun guard(context: GuardContext, action: Action): Decision = evaluate(context, action).decision

    override suspend fun authorize(context: GuardContext, action: Action): Boolean {
        val verdict = evaluate(context, action)
        val decision = verdict.decision
        if (decision !is Decision.Ask) {
            record(context, action, decision, approved = null)
            return decision is Decision.Allow
        }
        val key = verdict.approvalKey
        if (key != null && taskApprovals[context.taskId]?.contains(key) == true) {
            record(context, action, decision, approved = true)
            return true
        }
        val answer = try {
            approvals.request(ApprovalRequest(context.taskId, action, decision.prompt, decision.reason))
        } catch (e: CancellationException) {
            record(context, action, decision, approved = false)
            throw e
        } catch (e: Exception) {
            null
        }
        val approved = answer?.approved == true
        if (approved && answer?.scope == ApprovalScope.TASK && key != null) {
            taskApprovals.computeIfAbsent(context.taskId) { ConcurrentHashMap.newKeySet() }.add(key)
        }
        record(context, action, decision, approved)
        return approved
    }

    /** Forgets the TASK-scoped approvals of [taskId]. */
    fun endTask(taskId: String) {
        taskApprovals.remove(taskId)
    }

    private fun record(context: GuardContext, action: Action, decision: Decision, approved: Boolean?) {
        audit.record(AuditRecord(clock(), context.taskId, context.stepIndex, action, decision, approved))
    }

    private class Verdict(val decision: Decision, val approvalKey: String? = null)

    private fun evaluate(context: GuardContext, action: Action): Verdict {
        deny(context, action)?.let { return Verdict(Decision.Deny(it)) }
        return ask(context, action) ?: Verdict(Decision.Allow())
    }

    // ---- rule 1: deny ----

    private fun deny(context: GuardContext, action: Action): String? {
        val screen = context.screen
        if (screen?.secure == true && action.touchesDevice() && !action.isNavigation()) {
            return "secure screen: the user must take over"
        }
        if (action is Action.TypeText && screen != null && typeTarget(screen, action)?.password == true) {
            return "typing into a password field"
        }
        val skill = context.skill ?: return null
        if (skill.apps.isEmpty()) return null
        when (action) {
            is Action.LaunchApp ->
                if (!permitted(skill, action.packageName)) return "package ${action.packageName} is not in the apps of skill ${skill.name}"
            is Action.OpenDeeplink -> {
                val pkg = action.packageName
                if (pkg != null && !permitted(skill, pkg)) return "package $pkg is not in the apps of skill ${skill.name}"
            }
            is Action.Tap, is Action.LongPress, is Action.TypeText -> {
                val pkg = screen?.packageName
                if (pkg != null && !permitted(skill, pkg)) return "screen package $pkg is outside the apps of skill ${skill.name}"
            }
            else -> Unit
        }
        return null
    }

    private fun permitted(skill: Skill, packageName: String): Boolean =
        packageName in skill.apps || packageName == selfPackage || packageName in launcherPackages

    private fun Action.isNavigation(): Boolean = this is Action.Back || this is Action.Home || this is Action.Wait

    private fun Action.touchesDevice(): Boolean = this !is Action.AskUser && this !is Action.Finish

    // ---- rule 2: ask ----

    private fun ask(context: GuardContext, action: Action): Verdict? = when (action) {
        is Action.Tap, is Action.LongPress, is Action.TypeText -> askForWord(context, action)
        is Action.OpenDeeplink -> askForDeeplink(context.skill, action)
        else -> null
    }

    private fun askForWord(context: GuardContext, action: Action): Verdict? {
        val words = wordsFor(context.skill)
        val node = context.screen?.let { node(it, action) }
        val candidates = listOfNotNull(
            action.target?.let { "target" to it },
            node?.text?.let { "node text" to it },
            node?.description?.let { "node description" to it },
        )
        var best: Triple<String, String, String>? = null // word, where, text
        for ((where, text) in candidates) {
            val word = ConfirmWords.longestIn(text, words) ?: continue
            if (best == null || word.length > best.first.length) best = Triple(word, where, text)
        }
        val (word, where, text) = best ?: return null
        val label = text.trim().ellipsize(LABEL_MAX)
        val prompt = when (action) {
            is Action.LongPress -> "即将长按「$label」，是否继续？"
            is Action.TypeText -> if (action.submit) "即将在「$label」输入并提交，是否继续？" else "即将在「$label」输入内容，是否继续？"
            else -> "即将点击「$label」，是否继续？"
        }
        return Verdict(Decision.Ask(prompt, "confirmation word '$word' in $where"), "word:${word.lowercase()}")
    }

    private fun askForDeeplink(skill: Skill?, action: Action.OpenDeeplink): Verdict? {
        val target = SchemeHost.of(action.uri)
        if (skill != null && target != null && skill.deeplinks.values.any { SchemeHost.of(it) == target }) return null
        val reason = when {
            skill == null -> "deeplink without a skill"
            else -> "deeplink matches none of the deeplinks of skill ${skill.name}"
        }
        val prompt = "即将打开链接「${action.uri.ellipsize(URI_MAX)}」，是否继续？"
        return Verdict(Decision.Ask(prompt, reason), "deeplink:${target ?: action.uri}")
    }

    private fun wordsFor(skill: Skill?): List<String> =
        if (skill == null || skill.confirmBefore.isEmpty()) baseWords else ConfirmWords.normalize(baseWords + skill.confirmBefore)

    // ---- node resolution ----

    private fun node(screen: ScreenState, action: Action): UiNode? = when (action) {
        is Action.Tap -> byId(screen, action.nodeId) ?: action.point?.let { atPoint(screen, it) }
        is Action.LongPress -> byId(screen, action.nodeId) ?: action.point?.let { atPoint(screen, it) }
        is Action.TypeText -> typeTarget(screen, action)
        else -> null
    }

    private fun typeTarget(screen: ScreenState, action: Action.TypeText): UiNode? =
        if (action.nodeId != null) byId(screen, action.nodeId) else focusedEditable(screen)

    private fun byId(screen: ScreenState, id: Int?): UiNode? =
        id?.let { wanted -> screen.nodes.firstOrNull { it.id == wanted } }

    private fun focusedEditable(screen: ScreenState): UiNode? =
        screen.nodes.firstOrNull { it.focused && it.editable }

    /** Smallest node whose bounds contain [point]; on equal area the later (usually deeper) node. */
    private fun atPoint(screen: ScreenState, point: Point): UiNode? {
        var best: UiNode? = null
        var bestArea = Long.MAX_VALUE
        for (node in screen.nodes) {
            val b = node.bounds
            if (point.x !in b.left..b.right || point.y !in b.top..b.bottom) continue
            val area = (b.right - b.left).toLong() * (b.bottom - b.top).toLong()
            if (area <= bestArea) {
                best = node
                bestArea = area
            }
        }
        return best
    }

    private fun String.ellipsize(max: Int): String = if (length <= max) this else take(max - 1) + "…"

    companion object {
        /** Common home-screen packages: AOSP, Pixel, Samsung, Xiaomi, Huawei, Honor, OPPO/realme, vivo, OnePlus, Meizu. */
        val DEFAULT_LAUNCHERS: Set<String> = setOf(
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.hihonor.android.launcher",
            "com.oppo.launcher",
            "com.bbk.launcher2",
            "net.oneplus.launcher",
            "com.meizu.flyme.launcher",
        )

        /** Built-in confirmation words; skills and [extraConfirmWords] can only add to them. */
        val BUILT_IN_CONFIRM_WORDS: List<String> = ConfirmWords.BUILT_IN

        private const val LABEL_MAX = 24
        private const val URI_MAX = 80
    }
}
