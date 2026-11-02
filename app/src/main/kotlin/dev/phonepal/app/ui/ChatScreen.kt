package dev.phonepal.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonepal.api.ApprovalAnswer
import dev.phonepal.api.ApprovalScope
import dev.phonepal.app.AppGraph
import dev.phonepal.app.R
import dev.phonepal.app.session.AgentSession
import dev.phonepal.app.session.ChatItem
import dev.phonepal.app.session.PendingApproval
import dev.phonepal.hands.PhonePalAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(graph: AppGraph, onOpenSettings: () -> Unit) {
    val session = graph.session
    val items by session.items.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val question by session.question.collectAsStateWithLifecycle()
    val approval by session.approval.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val service by PhonePalAccessibilityService.instance.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationsGranted = rememberNotificationPermission()

    Column(Modifier.fillMaxSize()) {
        if (service == null) {
            Banner(stringResource(R.string.banner_accessibility), stringResource(R.string.banner_accessibility_action)) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (!settings.configured) {
            Banner(stringResource(R.string.banner_models), stringResource(R.string.banner_models_action), onOpenSettings)
        }
        if (!notificationsGranted.granted) {
            Banner(stringResource(R.string.banner_notifications), stringResource(R.string.banner_notifications_action), notificationsGranted.request)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        val listState = rememberLazyListState()
        LaunchedEffect(items.size, approval) {
            val count = items.size + if (approval != null) 1 else 0
            if (count > 0) listState.animateScrollToItem(count - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (items.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.chat_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(items, key = { it.id }) { ChatRow(it) }
            approval?.let { pending -> item(key = "approval-${pending.id}") { ApprovalCard(pending, session) } }
        }

        if (busy && status != null) {
            Text(
                status.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        ChatInput(session, busy = busy, answering = question != null)
    }
}

@Composable
private fun ChatInput(session: AgentSession, busy: Boolean, answering: Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val busyText = stringResource(R.string.chat_busy)
    val canSend = text.isNotBlank() && (!busy || answering)
    fun send() {
        when (session.submit(text)) {
            AgentSession.Submit.Busy -> hint = busyText
            AgentSession.Submit.Empty -> Unit
            else -> {
                text = ""
                hint = null
            }
        }
    }
    hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(if (answering) R.string.chat_answer_hint else R.string.chat_input_hint)) },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
        )
        Spacer(Modifier.padding(4.dp))
        if (busy && !answering) {
            FilledIconButton(onClick = session::stop) {
                Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop))
            }
        } else {
            FilledIconButton(onClick = ::send, enabled = canSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
            }
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    when (item) {
        is ChatItem.User -> Bubble(item.text, mine = true)
        is ChatItem.Assistant -> Bubble(item.text, mine = false)
        is ChatItem.Progress -> Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            color = when (item.ok) {
                false -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        is ChatItem.Question -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(if (item.takeover) R.string.chat_takeover_label else R.string.chat_question_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(item.text, style = MaterialTheme.typography.bodyLarge)
                if (item.takeover) Text(stringResource(R.string.chat_takeover_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        is ChatItem.Error -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.chat_error_label), style = MaterialTheme.typography.labelLarge)
                Text(item.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ApprovalCard(pending: PendingApproval, session: AgentSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var responding by remember(pending.id) { mutableStateOf(false) }
    fun approve(approvalScope: ApprovalScope) {
        if (responding) return
        responding = true
        scope.launch {
            // The approved coordinates belong to the app behind this confirmation card.
            // The operator re-observes and rejects the action if the page has changed.
            if (context.findActivity()?.moveTaskToBack(true) == true) delay(650)
            session.answerApproval(ApprovalAnswer(true, approvalScope), pending.id)
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.approval_title), style = MaterialTheme.typography.titleSmall)
            Text(pending.request.prompt, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { approve(ApprovalScope.ONCE) }, enabled = !responding) {
                    Text(stringResource(R.string.approval_once))
                }
                FilledTonalButton(onClick = { approve(ApprovalScope.TASK) }, enabled = !responding) {
                    Text(stringResource(R.string.approval_task))
                }
            }
            OutlinedButton(onClick = { session.answerApproval(ApprovalAnswer(false), pending.id) }) {
                Text(stringResource(R.string.approval_deny))
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}

@Composable
private fun Banner(text: String, action: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

private class NotificationPermission(val granted: Boolean, val request: () -> Unit)

/** POST_NOTIFICATIONS on Android 13+: asked once automatically, then via the banner. */
@Composable
private fun rememberNotificationPermission(): NotificationPermission {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NotificationPermission(true) {}
    val context = LocalContext.current
    fun check() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(check()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = check() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return NotificationPermission(granted) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
