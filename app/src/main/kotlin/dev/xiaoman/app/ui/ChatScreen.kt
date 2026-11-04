package dev.xiaoman.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.hands.XiaomanAccessibilityService

@Composable
fun ChatScreen(graph: AppGraph, onOpenSettings: () -> Unit) {
    val session = graph.session
    val items by session.items.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val question by session.question.collectAsStateWithLifecycle()
    val approval by session.approval.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val service by XiaomanAccessibilityService.instance.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val notificationsGranted = rememberNotificationPermission()
    var draft by rememberSaveable { mutableStateOf("") }
    val rows = remember(items) { chatTimeline(items) }
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }
    LaunchedEffect(approval?.id) {
        if (approval != null) { focus.clearFocus(); keyboard?.hide() }
    }
    Column(Modifier.fillMaxSize()) {
        ChatHeader(ready = settings.configured && service != null, busy = busy,
            attention = approval != null || question != null)
        if (service == null) {
            Banner(stringResource(R.string.banner_accessibility), stringResource(R.string.banner_accessibility_action)) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (!settings.configured) Banner(stringResource(R.string.banner_models), stringResource(R.string.banner_models_action), onOpenSettings)
        if (!notificationsGranted.granted) Banner(stringResource(R.string.banner_notifications), stringResource(R.string.banner_notifications_action), notificationsGranted.request)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (items.isEmpty()) item { ChatWelcome { draft = it } }
            items(rows, key = { it.id }) { row ->
                when (row) {
                    is TimelineRow.Message -> ChatMessage(row.item)
                    is TimelineRow.Process -> ProcessCard(row.entries)
                }
            }
        }
        if (approval != null) {
            ApprovalCard(approval!!, session)
        } else {
            if (busy) TaskStatus(status ?: "正在想办法…", session::stop)
            ChatInput(draft, { draft = it }, session, busy, question != null)
        }
    }
}

@Composable
private fun ChatInput(text: String, onChange: (String) -> Unit, session: AgentSession, busy: Boolean, answering: Boolean) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canSend = text.isNotBlank() && (!busy || answering)
    fun send() {
        if (!canSend) return
        when (session.submit(text)) {
            AgentSession.Submit.Busy, AgentSession.Submit.Empty -> Unit
            else -> { onChange(""); focus.clearFocus(); keyboard?.hide() }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text, onValueChange = onChange, modifier = Modifier.weight(1f),
                placeholder = { Text(if (answering) "告诉我就好…" else "说说你想做什么…", style = MaterialTheme.typography.bodyMedium) },
                shape = MaterialTheme.shapes.large, textStyle = MaterialTheme.typography.bodyMedium,
                minLines = 1, maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { if (busy && !answering) session.stop() else send() },
                enabled = (busy && !answering) || canSend, modifier = Modifier.padding(bottom = 4.dp).size(48.dp)) {
                Icon(if (busy && !answering) Icons.Default.Stop else Icons.Default.ArrowUpward,
                    contentDescription = stringResource(if (busy && !answering) R.string.chat_stop else R.string.chat_send))
            }
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
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
