package dev.xiaoman.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.xiaoman.app.XiaomanApp
import kotlinx.coroutines.launch

/**
 * Debug-build bridge for the `xiaoman` CLI:
 * `adb shell am broadcast -n dev.xiaoman.app/.debug.DebugReceiver -a dev.xiaoman.debug.RUN --es goal "打开设置" --es run_id r1`.
 *
 * RUN hands the task to the agent session (the foreground [dev.xiaoman.app.AgentService] is
 * started for it); approvals and questions still go to the user through the app. Every RUN ends
 * with a `Failed` or `PhoneTaskFinished` logcat line for its run id.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as XiaomanApp).graph
        val command = DebugCommand.parse(intent.action, intent::getStringExtra, graph.runIds::generate)
        when (command) {
            null -> Log.w(TAG, "debug: unknown action ${intent.action}")
            is DebugCommand.Run -> {
                Log.i(TAG, "debug: run ${command.runId} skill=${command.skill ?: "-"}")
                graph.session.runPhoneTask(command.goal, command.skill, command.runId)
            }
            is DebugCommand.InvalidRun -> {
                Log.w(TAG, "debug: run rejected: ${command.message}")
                graph.session.fail(command.runId, command.message)
            }
            DebugCommand.Stop -> {
                Log.i(TAG, "debug: stop")
                graph.session.stop()
            }
            DebugCommand.ReloadSkills -> {
                val pending = goAsync()
                graph.scope.launch {
                    try {
                        graph.startup.await()
                        val result = graph.skills.reload()
                        graph.logSkillProblems(result.skills.size, result.problems)
                    } catch (e: Exception) {
                        Log.w(TAG, "reload-skills: failed: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Xiaoman"
    }
}
