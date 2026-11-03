package dev.phonepal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.phonepal.app.AppGraph
import dev.phonepal.app.R
import dev.phonepal.mcp.CatalogServer
import dev.phonepal.mcp.ConfirmPolicy
import dev.phonepal.mcp.CustomMcpServer
import dev.phonepal.mcp.McpCatalog
import dev.phonepal.mcp.McpPlatform
import dev.phonepal.mcp.McpSelection
import dev.phonepal.mcp.ServerStatus
import kotlinx.coroutines.launch

/** Services that work on Android, grouped by category. */
private val catalog: List<CatalogServer> by lazy { McpCatalog.builtIn.availableOn(McpPlatform.ANDROID) }

/** Ids ticked by 推荐组合: free or easy to obtain, read-only. */
private val RECOMMENDED = setOf("amap", "aihot")

/** One-tap MCP services: tick a service, paste its key, save. Changes apply from the next chat message. */
@Composable
fun McpScreen(graph: AppGraph) {
    val store = graph.mcpSettings
    var selection by remember { mutableStateOf(store.current) }
    var saved by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val status = remember { mutableStateMapOf<String, ServerStatus>() }
    val scope = rememberCoroutineScope()

    fun update(next: McpSelection) {
        selection = next
        saved = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.mcp_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.mcp_intro), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.mcp_enabled_count, selection.enabled.size + selection.custom.size), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { update(selection.copy(enabled = selection.enabled + RECOMMENDED)) }) {
                Text(stringResource(R.string.mcp_recommended))
            }
        }

        catalog.groupBy { it.category }.forEach { (category, servers) ->
            Text(category, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            servers.forEach { server ->
                ServiceCard(
                    server = server,
                    selection = selection,
                    status = status[server.id],
                    onChange = ::update,
                )
            }
        }

        HorizontalDivider()
        CustomServers(selection, status, ::update)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                store.save(selection)
                saved = true
            }) { Text(stringResource(R.string.settings_save)) }
            OutlinedButton(enabled = !testing, onClick = {
                testing = true
                scope.launch {
                    try {
                        status.clear()
                        graph.testMcp(selection).forEach { status[it.name] = it }
                    } finally {
                        testing = false
                    }
                }
            }) { Text(stringResource(if (testing) R.string.mcp_testing else R.string.mcp_test)) }
            if (saved) Text(stringResource(R.string.settings_saved))
        }
    }
}

@Composable
private fun ServiceCard(server: CatalogServer, selection: McpSelection, status: ServerStatus?, onChange: (McpSelection) -> Unit) {
    val enabled = server.id in selection.enabled
    val uri = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(server.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(if (server.official) R.string.mcp_official else R.string.mcp_community),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(server.description, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = enabled, onCheckedChange = { on ->
                    onChange(selection.copy(enabled = if (on) selection.enabled + server.id else selection.enabled - server.id))
                })
            }
            if (enabled) {
                server.keys.forEach { key ->
                    OutlinedTextField(
                        value = selection.keys[key.name].orEmpty(),
                        onValueChange = { onChange(selection.copy(keys = selection.keys + (key.name to it.trim()))) },
                        label = { Text(key.label) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    key.help?.let { help -> TextButton(onClick = { uri.openUri(help) }) { Text(stringResource(R.string.mcp_apply_key)) } }
                }
                val missing = server.keys.filter { selection.keys[it.name].isNullOrBlank() }.map { it.label }
                if (missing.isNotEmpty()) {
                    Text(stringResource(R.string.mcp_missing_key, missing.joinToString("、")), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            status?.let { StatusLine(it) }
        }
    }
}

@Composable
private fun StatusLine(status: ServerStatus) {
    if (status.connected) {
        Text(stringResource(R.string.mcp_connected, status.toolCount), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
    } else {
        Text(stringResource(R.string.mcp_failed, status.error ?: "?"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CustomServers(selection: McpSelection, status: Map<String, ServerStatus>, onChange: (McpSelection) -> Unit) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(ConfirmPolicy.WRITES) }
    val problem = url.takeIf { it.isNotBlank() }?.let { McpCatalog.validateCustomUrl(it) }

    Text(stringResource(R.string.mcp_custom_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.mcp_custom_hint), style = MaterialTheme.typography.bodySmall)
    selection.custom.forEachIndexed { i, c ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title.ifBlank { c.url }, style = MaterialTheme.typography.titleSmall)
                        Text(c.url.substringBefore('?'), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onChange(selection.copy(custom = selection.custom.filterIndexed { j, _ -> j != i })) }) {
                        Text(stringResource(R.string.mcp_remove))
                    }
                }
                status["custom-${i + 1}"]?.let { StatusLine(it) }
            }
        }
    }
    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.mcp_custom_name)) },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        value = url, onValueChange = { url = it.trim() }, label = { Text(stringResource(R.string.mcp_custom_url)) },
        isError = problem != null, supportingText = { problem?.let { Text(stringResource(R.string.mcp_custom_invalid, it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = auth, onValueChange = { auth = it }, label = { Text(stringResource(R.string.mcp_custom_auth)) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.mcp_custom_confirm), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            ConfirmPolicy.WRITES to R.string.mcp_confirm_writes,
            ConfirmPolicy.ALL to R.string.mcp_confirm_all,
            ConfirmPolicy.NONE to R.string.mcp_confirm_none,
        ).forEach { (policy, label) ->
            FilterChip(selected = confirm == policy, onClick = { confirm = policy }, label = { Text(stringResource(label)) })
        }
    }
    AssistChip(
        enabled = url.isNotBlank() && problem == null,
        onClick = {
            val headers = auth.trim().takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to it) }.orEmpty()
            onChange(selection.copy(custom = selection.custom + CustomMcpServer(title.trim(), url, headers, confirm)))
            title = ""; url = ""; auth = ""; confirm = ConfirmPolicy.WRITES
        },
        label = { Text(stringResource(R.string.mcp_custom_add)) },
    )
}
