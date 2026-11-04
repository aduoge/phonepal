package dev.xiaoman.app.debug

import dev.xiaoman.app.RunIds

/** A parsed debug-bridge broadcast (docs/ARCHITECTURE.md, Debug bridge). */
sealed class DebugCommand {
    data class Run(val goal: String, val skill: String?, val runId: String) : DebugCommand()

    /** A RUN that cannot run; the receiver still ends the run with Failed so `xiaoman run --follow` returns. */
    data class InvalidRun(val runId: String, val message: String) : DebugCommand()

    data object Stop : DebugCommand()

    data object ReloadSkills : DebugCommand()

    companion object {
        const val ACTION_RUN = "dev.xiaoman.debug.RUN"
        const val ACTION_STOP = "dev.xiaoman.debug.STOP"
        const val ACTION_RELOAD_SKILLS = "dev.xiaoman.debug.RELOAD_SKILLS"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_SKILL = "skill"
        const val EXTRA_RUN_ID = "run_id"

        /**
         * @param extra string extra lookup (`Intent::getStringExtra`)
         * @param newRunId used when `run_id` is missing; an invalid `run_id` is rejected.
         * @return null for an unknown action.
         */
        fun parse(action: String?, extra: (String) -> String?, newRunId: () -> String): DebugCommand? = when (action) {
            ACTION_RUN -> parseRun(extra, newRunId)
            ACTION_STOP -> Stop
            ACTION_RELOAD_SKILLS -> ReloadSkills
            else -> null
        }

        private fun parseRun(extra: (String) -> String?, newRunId: () -> String): DebugCommand {
            val rawId = extra(EXTRA_RUN_ID)?.trim()?.takeIf { it.isNotEmpty() }
            if (rawId != null && !RunIds.isValid(rawId)) {
                return InvalidRun(newRunId(), "invalid run_id '$rawId': use letters, digits, '.', '_' or '-'")
            }
            val runId = rawId ?: newRunId()
            val goal = extra(EXTRA_GOAL)?.trim()?.takeIf { it.isNotEmpty() } ?: return InvalidRun(runId, "missing extra 'goal'")
            val skill = extra(EXTRA_SKILL)?.trim()?.takeIf { it.isNotEmpty() }
            return Run(goal, skill, runId)
        }
    }
}
