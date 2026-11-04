package dev.xiaoman.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (type specialUse) kept while the [AgentSession][dev.xiaoman.app.session.AgentSession]
 * is busy, so the agent keeps running while other apps are in front. The chat turn or phone task
 * itself runs in the session's scope; this service shows the status notification with a Stop
 * action and stops itself when the session goes idle.
 */
class AgentService : LifecycleService() {
    private val graph get() = (application as XiaomanApp).graph
    private var watching = false
    private var lastStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        if (!enterForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            val session = graph.session
            lifecycleScope.launch {
                combine(session.busy, session.status) { busy, status -> busy to status }.collect { (busy, status) ->
                    if (busy) {
                        graph.notifier.updateStatus(status)
                    } else {
                        stopForeground(Service.STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean = try {
        val notification = graph.notifier.status(graph.session.status.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifier.ID_STATUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_STATUS, notification)
        }
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException and friends: the task keeps running anyway.
        Log.w(TAG, "agent-service: cannot enter foreground: ${e.message}")
        false
    }

    companion object {
        private const val TAG = "Xiaoman"

        /**
         * Starts the service. May be refused when the app is in the background (Android 12+); the
         * session still runs the task, the process just has no foreground notification then.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "agent-service: not started: ${e.message}")
            false
        }
    }
}
