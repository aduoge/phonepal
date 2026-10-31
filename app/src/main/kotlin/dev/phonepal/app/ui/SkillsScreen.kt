package dev.phonepal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.phonepal.api.Skill
import dev.phonepal.api.SkillProblem
import dev.phonepal.api.SkillRepository
import dev.phonepal.app.AppGraph
import dev.phonepal.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SkillsState(val skills: List<Skill> = emptyList(), val problems: List<SkillProblem> = emptyList(), val loading: Boolean = true)

class SkillsViewModel(
    private val repository: SkillRepository,
    private val ready: suspend () -> Unit,
    private val onReloaded: (Int, List<SkillProblem>) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(SkillsState())
    val state: StateFlow<SkillsState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            ready()
            val result = repository.reload()
            onReloaded(result.skills.size, result.problems)
            _state.value = SkillsState(result.skills, result.problems, loading = false)
        }
    }
}

@Composable
fun SkillsScreen(graph: AppGraph) {
    val vm = viewModel { SkillsViewModel(graph.skills, { graph.startup.await() }, graph::logSkillProblems) }
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.skills_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::reload, enabled = !state.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(stringResource(R.string.skills_reload), modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.loading && state.skills.isEmpty()) {
                item { Text(stringResource(R.string.skills_empty, graph.skillsDir.path), style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.skills, key = { it.name }) { skill ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(skill.name, style = MaterialTheme.typography.titleMedium)
                        Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                        if (skill.apps.isNotEmpty()) {
                            Text(stringResource(R.string.skills_apps, skill.apps.joinToString()), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(skill.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.problems.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Text(stringResource(R.string.skills_problems), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                items(state.problems) { p ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.source + (p.line?.let { ":$it" } ?: ""), style = MaterialTheme.typography.labelMedium)
                            Text(p.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
