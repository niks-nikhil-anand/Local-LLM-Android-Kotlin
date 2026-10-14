package com.example.localai.ui.screens.scheduled

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
fun ScheduledTasksScreen(
    enabledTaskIds: Set<String>,
    onToggleTask: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    SecondaryScreen(
        title = "Scheduled Tasks",
        onBack = onBack,
        banner = "Simulated: nothing is scheduled and no background work runs.",
        modifier = modifier
    ) {
        items(MockWorkspaceRepository.scheduledTasks, key = { it.id }) { task ->
            val enabled = task.id in enabledTaskIds
            SurfaceCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .toggleable(
                            value = enabled,
                            onValueChange = { onToggleTask(task.id) },
                            role = Role.Switch
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = task.schedule,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = task.description,
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
