package dev.phonepal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.phonepal.api.MemoryStore
import dev.phonepal.api.Note
import dev.phonepal.app.AppGraph
import dev.phonepal.app.R
import dev.phonepal.memory.BasicMemoryFormat
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A row of the memory list: every note when the query is blank, search hits otherwise. */
data class MemoryRow(val permalink: String, val title: String, val detail: String)

data class MemoryState(
    val query: String = "",
    val rows: List<MemoryRow> = emptyList(),
    val loading: Boolean = true,
    /** The opened note, rendered as Markdown text. */
    val open: Note? = null,
    val openMarkdown: String = "",
    val openMissing: Boolean = false,
)

@OptIn(FlowPreview::class)
class MemoryViewModel(private val memory: MemoryStore) : ViewModel() {
    private val _state = MutableStateFlow(MemoryState())
    val state: StateFlow<MemoryState> = _state.asStateFlow()
    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch { query.debounce(250).collect { refresh() } }
    }

    fun setQuery(text: String) {
        _state.update { it.copy(query = text) }
        query.value = text
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val q = _state.value.query.trim()
            val rows = runCatching {
                if (q.isEmpty()) {
                    memory.list().sortedByDescending { it.updatedAtMillis }.map { MemoryRow(it.permalink, it.title, it.permalink) }
                } else {
                    memory.search(q, limit = 50).map { MemoryRow(it.permalink, it.title, it.snippet.replace('\n', ' ')) }
                }
            }.getOrDefault(emptyList())
            _state.update { it.copy(rows = rows, loading = false) }
        }
    }

    fun open(permalink: String) {
        viewModelScope.launch {
            val note = runCatching { memory.read(permalink) }.getOrNull()
            _state.update { it.copy(open = note, openMarkdown = note?.let(BasicMemoryFormat::render).orEmpty(), openMissing = note == null) }
        }
    }

    fun close() = _state.update { it.copy(open = null, openMarkdown = "", openMissing = false) }

    fun delete(permalink: String) {
        viewModelScope.launch {
            runCatching { memory.delete(permalink) }
            close()
            refresh()
        }
    }
}

@Composable
fun MemoryScreen(graph: AppGraph) {
    val vm = viewModel { MemoryViewModel(graph.memory) }
    val state by vm.state.collectAsStateWithLifecycle()
    val note = state.open
    if (note != null) NoteView(note, state.openMarkdown, onBack = vm::close, onDelete = { vm.delete(note.permalink) }) else MemoryList(state, vm)
}

@Composable
private fun MemoryList(state: MemoryState, vm: MemoryViewModel) {
    LaunchedEffect(Unit) { vm.refresh() }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear)) }
                }
            },
            placeholder = { Text(stringResource(R.string.memory_search_hint)) },
        )
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.loading && state.rows.isEmpty()) {
            Text(
                stringResource(if (state.query.isBlank()) R.string.memory_empty else R.string.memory_no_hits),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.openMissing) Text(stringResource(R.string.memory_not_found), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.rows, key = { it.permalink }) { row ->
                ListItem(
                    headlineContent = { Text(row.title) },
                    supportingContent = { Text(row.detail, maxLines = 2, style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.clickable { vm.open(row.permalink) },
                )
            }
        }
    }
}

@Composable
private fun NoteView(note: Note, markdown: String, onBack: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(note.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.memory_delete)) }
        }
        SelectionContainer {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(markdown, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.memory_delete_title)) },
            text = { Text(stringResource(R.string.memory_delete_message, note.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDelete()
                }) { Text(stringResource(R.string.memory_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
