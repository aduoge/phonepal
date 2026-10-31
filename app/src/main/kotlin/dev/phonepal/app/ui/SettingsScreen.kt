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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.phonepal.app.AppGraph
import dev.phonepal.app.R
import dev.phonepal.app.settings.SettingsForm

@Composable
fun SettingsScreen(graph: AppGraph) {
    val store = graph.settings
    var form by remember { mutableStateOf(SettingsForm.of(store.current)) }
    var saved by remember { mutableStateOf(false) }
    val parsed = form.toSettings()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)

        ModelSection(stringResource(R.string.settings_chat_model), form.chat) {
            form = form.copy(chat = it)
            saved = false
        }
        HorizontalDivider()
        ModelSection(stringResource(R.string.settings_operator_model), form.operator) {
            form = form.copy(operator = it)
            saved = false
        }
        TextButton(onClick = {
            form = form.copy(operator = form.operator.copy(baseUrl = form.chat.baseUrl, apiKey = form.chat.apiKey))
            saved = false
        }) { Text(stringResource(R.string.settings_copy_chat)) }
        HorizontalDivider()

        Text(stringResource(R.string.settings_agent), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = form.maxSteps,
            onValueChange = {
                form = form.copy(maxSteps = it)
                saved = false
            },
            label = { Text(stringResource(R.string.settings_max_steps)) },
            isError = !form.maxStepsValid,
            supportingText = { if (!form.maxStepsValid) Text(stringResource(R.string.settings_invalid_number)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_learn), modifier = Modifier.weight(1f))
            Switch(checked = form.learnFromSuccess, onCheckedChange = {
                form = form.copy(learnFromSuccess = it)
                saved = false
            })
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = parsed != null, onClick = {
                parsed?.let {
                    store.save(it)
                    saved = true
                }
            }) { Text(stringResource(R.string.settings_save)) }
            if (saved) {
                Text(
                    stringResource(R.string.settings_saved),
                    modifier = Modifier.padding(start = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        HorizontalDivider()
        Text(stringResource(R.string.settings_folders), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_folder_skills, graph.skillsDir.path), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.settings_folder_memory, graph.memoryDir.path), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.settings_folder_traces, graph.tracesDir.path), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ModelSection(title: String, model: SettingsForm.ModelForm, onChange: (SettingsForm.ModelForm) -> Unit) {
    var showKey by remember { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = model.baseUrl,
        onValueChange = { onChange(model.copy(baseUrl = it)) },
        label = { Text(stringResource(R.string.settings_base_url)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.model,
        onValueChange = { onChange(model.copy(model = it)) },
        label = { Text(stringResource(R.string.settings_model)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.apiKey,
        onValueChange = { onChange(model.copy(apiKey = it)) },
        label = { Text(stringResource(R.string.settings_api_key)) },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (showKey) R.string.settings_hide_key else R.string.settings_show_key),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.temperature,
        onValueChange = { onChange(model.copy(temperature = it)) },
        label = { Text(stringResource(R.string.settings_temperature)) },
        isError = !model.temperatureValid,
        supportingText = { if (!model.temperatureValid) Text(stringResource(R.string.settings_invalid_number)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
