package dev.phonepal.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.phonepal.app.AppGraph
import dev.phonepal.app.R

enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    CHAT(R.string.nav_chat, Icons.AutoMirrored.Filled.Chat),
    SKILLS(R.string.nav_skills, Icons.Filled.Extension),
    MEMORY(R.string.nav_memory, Icons.Filled.Psychology),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
}

@Composable
fun PhonePalRoot(graph: AppGraph) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (tab) {
                Tab.CHAT -> ChatScreen(graph, onOpenSettings = { tab = Tab.SETTINGS })
                Tab.SKILLS -> SkillsScreen(graph)
                Tab.MEMORY -> MemoryScreen(graph)
                Tab.SETTINGS -> SettingsScreen(graph)
            }
        }
    }
}
