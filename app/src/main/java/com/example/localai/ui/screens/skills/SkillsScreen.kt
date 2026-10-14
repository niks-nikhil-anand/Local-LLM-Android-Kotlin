package com.example.localai.ui.screens.skills

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.localai.data.mock.MockWorkspaceRepository
import com.example.localai.ui.components.SecondaryScreen
import com.example.localai.ui.components.SurfaceCard

@Composable
fun SkillsScreen(
    enabledSkillIds: Set<String>,
    onToggleSkill: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    SecondaryScreen(
        title = "Skills",
        onBack = onBack,
        banner = "Simulated: toggles only change this demo. Skills do not affect replies.",
        modifier = modifier
    ) {
        items(MockWorkspaceRepository.skills, key = { it.id }) { skill ->
            val enabled = skill.id in enabledSkillIds
            SurfaceCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .toggleable(
                            value = enabled,
                            onValueChange = { onToggleSkill(skill.id) },
                            role = Role.Switch
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(skill.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = enabled, onCheckedChange = null)
                }
            }
        }
    }
}
