package dev.xiaoman.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalScope
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.PendingApproval
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pinned above navigation, outside the scrolling transcript; no keyboard can cover its controls. */
@Composable
internal fun ApprovalCard(pending: PendingApproval, session: AgentSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var responding by remember(pending.id) { mutableStateOf(false) }
    var more by remember(pending.id) { mutableStateOf(false) }
    fun approve(approvalScope: ApprovalScope) {
        if (responding) return
        responding = true
        scope.launch {
            // Keep the screen-match guard: approval belongs to the observed app, never this card.
            if (context.findActivity()?.moveTaskToBack(true) == true) delay(650)
            session.answerApproval(ApprovalAnswer(true, approvalScope), pending.id)
        }
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Assertive }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("这一步，请你确认", style = MaterialTheme.typography.titleSmall)
            Text(pending.request.prompt, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.heightIn(max = 110.dp).verticalScroll(rememberScrollState()))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { responding = true; session.answerApproval(ApprovalAnswer(false), pending.id) },
                    enabled = !responding, modifier = Modifier.weight(1f)) { Text("暂不允许") }
                Button(onClick = { approve(ApprovalScope.ONCE) }, enabled = !responding, modifier = Modifier.weight(1f)) { Text("允许一次") }
            }
            if (more) {
                TextButton(onClick = { approve(ApprovalScope.TASK) }, enabled = !responding) {
                    Text("本次任务的同类操作都允许", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                TextButton(onClick = { more = true }, enabled = !responding, contentPadding = PaddingValues(0.dp)) {
                    Text("更多选项", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}
