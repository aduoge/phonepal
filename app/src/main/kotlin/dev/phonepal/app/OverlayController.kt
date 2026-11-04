package dev.phonepal.app

import android.content.Intent
import dev.phonepal.app.session.AgentSession
import dev.phonepal.hands.AccessibilityDevice
import dev.phonepal.hands.PhonePalAccessibilityService
import dev.phonepal.hands.StatusOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Shows the hands' [StatusOverlay] (step text + Stop) while the session is busy and the
 * accessibility service is connected; the overlay must be created with the connected service.
 */
class OverlayController(
    private val session: AgentSession,
    private val device: AccessibilityDevice,
    private val services: StateFlow<PhonePalAccessibilityService?> = PhonePalAccessibilityService.instance,
    private val uiVisible: StateFlow<Boolean> = MutableStateFlow(false),
) {
    private var overlay: StatusOverlay? = null
    private var owner: PhonePalAccessibilityService? = null

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.Main.immediate) {
        combine(services, session.busy, session.status, uiVisible) { service, busy, status, visible -> Triple(service, busy && !visible, status) }
            .collect { (service, busy, status) ->
                if (!busy || service == null) {
                    remove()
                    return@collect
                }
                if (owner !== service) {
                    remove()
                    overlay = StatusOverlay(service).also {
                        it.onStop = { session.stop() }
                        it.onOpen = { service.startActivity(Intent(service, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                        device.overlay = it
                        it.show()
                    }
                    owner = service
                }
                overlay?.update(status ?: "PhonePal")
            }
    }

    private fun remove() {
        overlay?.hide()
        overlay = null
        owner = null
        device.overlay = null
    }
}
