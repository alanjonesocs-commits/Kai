package com.inspiredandroid.kai.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.data.DataRepository
import com.inspiredandroid.kai.data.MemoryAssessment
import com.inspiredandroid.kai.data.MemoryEntry
import kotlinx.coroutines.delay

/** Read-only dashboard backed by NIA15's existing repositories. */
@Composable
fun BrainDashboardScreen(repository: DataRepository, onBack: () -> Unit) {
    var memories by remember { mutableStateOf(repository.getMemories()) }
    var services by remember { mutableStateOf(repository.getConfiguredServiceInstances()) }
    var tools by remember { mutableStateOf(repository.getToolDefinitions()) }
    var search by remember { mutableStateOf("") }

    LaunchedEffect(repository) {
        while (true) {
            memories = repository.getMemories()
            services = repository.getConfiguredServiceInstances()
            tools = repository.getToolDefinitions()
            delay(3_000)
        }
    }

    val conflicts = memories.count { it.resolutionStatus == MemoryAssessment.NEEDS_VERIFICATION }
    val filtered = memories.filter {
        search.isBlank() || it.key.contains(search, ignoreCase = true) ||
            it.content.contains(search, ignoreCase = true) ||
            it.category.name.contains(search, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("NIA brain monitor", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        Text("Live repository snapshot · refreshes every 3 seconds",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Memories", memories.size.toString(), Modifier.weight(1f))
            MetricCard("Conflicts", conflicts.toString(), Modifier.weight(1f))
            MetricCard("Tools", tools.size.toString(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Configured services", services.size.toString(), Modifier.weight(1f))
            MetricCard("Memory engine", if (repository.isMemoryEnabled()) "Enabled" else "Disabled", Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("System boundaries", style = MaterialTheme.typography.titleMedium)
                StatusLine("Memory records", "Read from the app's current DataRepository")
                StatusLine("Models and providers", "Configuration count only; live reachability not exposed here")
                StatusLine("Linux memory node", "Not connected / not verified")
                StatusLine("Reasoning and learning telemetry", "Not exposed by current repository")
                StatusLine("Approval queue", "Not yet implemented as a distinct workflow")
            }
        }
        Text("Memory browser (${filtered.size})", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = search, onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("Search key, content or category") }, singleLine = true)
        if (filtered.isEmpty()) Text("No matching memory records.")
        else filtered.forEach { entry -> MemoryRecordCard(entry) }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusLine(label: String, status: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MemoryRecordCard(entry: MemoryEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.key, style = MaterialTheme.typography.titleSmall)
            Text(entry.content, style = MaterialTheme.typography.bodyMedium)
            Text("Category: ${entry.category} · Claim: ${entry.claimStatus} · Fact: ${entry.factStatus}",
                style = MaterialTheme.typography.bodySmall)
            Text("Resolution: ${entry.resolutionStatus} · Hits: ${entry.hitCount}",
                style = MaterialTheme.typography.bodySmall)
            Text("Created: ${entry.createdAt} · Updated: ${entry.updatedAt}",
                style = MaterialTheme.typography.bodySmall)
            if (entry.provenance.isEmpty()) {
                Text("Provenance: not recorded", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            } else entry.provenance.forEach { source ->
                Text("Source: ${source.source ?: "unspecified"} · Conversation: ${source.conversationId ?: "none"} · Observed: ${source.observedAt}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
