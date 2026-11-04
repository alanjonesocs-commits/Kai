package com.inspiredandroid.kai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.diagnostics.NiaDiagnosticEvent
import com.inspiredandroid.kai.diagnostics.NiaDiagnostics

@Composable
internal fun DiagnosticsSection() {
    var events by remember { mutableStateOf(NiaDiagnostics.readAll().reversed()) }
    LaunchedEffect(Unit) { events = NiaDiagnostics.readAll().reversed() }

    Column(Modifier.fillMaxWidth()) {
        Text("Nia diagnostics", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Local error and trace history. Sensitive values are redacted; the newest 200 events are retained.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { events = NiaDiagnostics.readAll().reversed() }) {
                Text("Refresh")
            }
            OutlinedButton(onClick = {
                NiaDiagnostics.clear()
                events = emptyList()
            }) {
                Text("Clear log")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (events.isEmpty()) {
            Text("No diagnostic events recorded.", style = MaterialTheme.typography.bodyMedium)
        } else {
            events.take(30).forEach { event ->
                DiagnosticEventRow(event)
            }
        }
    }
}

@Composable
private fun DiagnosticEventRow(event: NiaDiagnosticEvent) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "${event.severity} · ${event.component} / ${event.operation}",
            style = MaterialTheme.typography.labelLarge,
            color = if (event.severity == "ERROR" || event.severity == "CRITICAL") {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(event.message, style = MaterialTheme.typography.bodySmall)
        Text(
            "${event.timestamp} · Trace ${event.traceId}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        event.exceptionType?.let {
            Text(it, style = MaterialTheme.typography.labelSmall)
        }
        event.stackTrace?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
