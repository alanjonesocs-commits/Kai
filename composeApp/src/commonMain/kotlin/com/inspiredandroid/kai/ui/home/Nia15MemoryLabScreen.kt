package com.inspiredandroid.kai.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.data.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

@Serializable
data class Nia15LabProvenance(
    val source: String?,
    val conversationId: String?,
    val observedAt: Long,
)

@Serializable
data class Nia15LabMemory(
    val id: String,
    val key: String,
    val content: String,
    val receiptStatus: String,
    val claimStatus: String,
    val factStatus: String,
    val resolutionStatus: String,
    val source: String?,
    val sourceConversationId: String?,
    val provenance: List<Nia15LabProvenance>,
    val captureDecision: String = "PENDING",
)

object Nia15LabMemoryData {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    private val assessments = setOf(
        "CONFIRMED",
        "REPORTED",
        "ASSUMED",
        "UNKNOWN",
        "NEEDS_VERIFICATION",
    )
    private val decisions = setOf("PENDING", "APPROVED", "REJECTED")

    fun seed(): List<Nia15LabMemory> = listOf(
        memory(
            id = "synthetic-001",
            content = "Fictional test user reported that the workspace is Room A.",
            conversationId = "synthetic-session-001",
            observedAt = 1_760_000_000_000,
        ),
        memory(
            id = "synthetic-002",
            content = "Fictional test user later reported that the workspace is Room B.",
            conversationId = "synthetic-session-002",
            observedAt = 1_760_000_100_000,
        ),
        memory(
            id = "synthetic-003",
            key = "desk_preference",
            content = "Fictional test user said they prefer a wooden desk.",
            conversationId = "synthetic-session-003",
            observedAt = 1_760_000_200_000,
        ),
    )

    fun encode(records: List<Nia15LabMemory>): String {
        validate(records)
        return json.encodeToString(records)
    }

    fun decode(raw: String): List<Nia15LabMemory> {
        val records = json.decodeFromString<List<Nia15LabMemory>>(raw)
        validate(records)
        return records
    }

    fun approve(records: List<Nia15LabMemory>, id: String): List<Nia15LabMemory> =
        updateDecision(records, id, "APPROVED")

    fun reject(records: List<Nia15LabMemory>, id: String): List<Nia15LabMemory> =
        updateDecision(records, id, "REJECTED")

    fun addSyntheticFollowUp(records: List<Nia15LabMemory>): List<Nia15LabMemory> {
        val nextNumber = records.size + 1
        val latestObservedAt = records.flatMap { it.provenance }.maxOfOrNull { it.observedAt } ?: 0L
        val next = memory(
            id = "synthetic-${nextNumber.toString().padStart(3, '0')}",
            content = "Fictional test user later reported that the workspace is Room C.",
            conversationId = "synthetic-session-${nextNumber.toString().padStart(3, '0')}",
            observedAt = latestObservedAt + 100_000,
        )
        return (records + next).also(::validate)
    }

    private fun updateDecision(
        records: List<Nia15LabMemory>,
        id: String,
        decision: String,
    ): List<Nia15LabMemory> {
        require(id in records.map { it.id }) { "Unknown synthetic memory ID" }
        return records.map { record ->
            if (record.id == id) record.copy(captureDecision = decision) else record
        }.also(::validate)
    }

    private fun memory(
        id: String,
        key: String = "workspace_room",
        content: String,
        conversationId: String,
        observedAt: Long,
    ) = Nia15LabMemory(
        id = id,
        key = key,
        content = content,
        receiptStatus = "CONFIRMED",
        claimStatus = "REPORTED",
        factStatus = "UNKNOWN",
        resolutionStatus = "NEEDS_VERIFICATION",
        source = "synthetic_fixture",
        sourceConversationId = conversationId,
        provenance = listOf(
            Nia15LabProvenance(
                source = "synthetic_fixture",
                conversationId = conversationId,
                observedAt = observedAt,
            ),
        ),
    )

    private fun validate(records: List<Nia15LabMemory>) {
        require(records.map { it.id }.distinct().size == records.size) {
            "Duplicate synthetic memory ID"
        }
        records.forEach { record ->
            require(record.id.isNotBlank() && record.key.isNotBlank() && record.content.isNotBlank()) {
                "Synthetic memory ID, key and content must not be blank"
            }
            require(record.receiptStatus in assessments)
            require(record.claimStatus in assessments)
            require(record.factStatus in assessments)
            require(record.resolutionStatus in assessments)
            require(record.captureDecision in decisions)
            record.provenance.forEach { provenance ->
                require(provenance.source == null || provenance.source.isNotBlank())
                require(provenance.conversationId == null || provenance.conversationId.isNotBlank())
                require(provenance.observedAt >= 0)
            }
        }
    }
}

@Composable
fun Nia15MemoryLabScreen(onBack: () -> Unit) {
    val appSettings = koinInject<AppSettings>()
    val rawAtStart = remember { appSettings.getNia15LabMemoriesJsonOrNull() }
    val initialLoad = remember {
        if (rawAtStart == null) {
            Result.success(Nia15LabMemoryData.seed())
        } else {
            runCatching { Nia15LabMemoryData.decode(rawAtStart) }
        }
    }

    var memories by remember { mutableStateOf(initialLoad.getOrElse { emptyList() }) }
    var loadError by remember { mutableStateOf(initialLoad.exceptionOrNull()?.message) }
    var search by remember { mutableStateOf("") }
    var retrievalEnabled by remember { mutableStateOf(appSettings.isNia15LabChatRetrievalEnabled()) }

    LaunchedEffect(rawAtStart) {
        if (rawAtStart == null && loadError == null) {
            appSettings.setNia15LabMemoriesJson(Nia15LabMemoryData.encode(memories))
        }
    }

    fun save(next: List<Nia15LabMemory>) {
        if (loadError != null) return
        try {
            appSettings.setNia15LabMemoriesJson(Nia15LabMemoryData.encode(next))
            memories = next
        } catch (error: Exception) {
            loadError = "Could not save the synthetic test data: ${error.message}"
        }
    }

    val visible = memories.filter {
        search.isBlank() ||
            it.key.contains(search, ignoreCase = true) ||
            it.content.contains(search, ignoreCase = true) ||
            it.captureDecision.contains(search, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("NIA15 Memory Lab", style = MaterialTheme.typography.headlineSmall)
        Text(
            "EXPERIMENTAL · SYNTHETIC DATA ONLY",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "This test area uses its own settings key. It never reads or changes canonical memories. Chat retrieval is separately controlled below and uses approved fictional records only.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Saved test records: ${memories.size}", style = MaterialTheme.typography.titleMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Connect synthetic memories to chat", style = MaterialTheme.typography.titleSmall)
                    Text("Only APPROVED fictional records are supplied to Nia's next chat prompt. Canonical memory storage is not changed.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = retrievalEnabled,
                    onCheckedChange = { enabled ->
                        appSettings.setNia15LabChatRetrievalEnabled(enabled)
                        retrievalEnabled = enabled
                    },
                    enabled = loadError == null,
                )
            }
        }

        if (loadError != null) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Test data could not be loaded safely", style = MaterialTheme.typography.titleMedium)
                    Text(loadError.orEmpty())
                    Text("No replacement data has been written.")
                }
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search synthetic memories") },
            singleLine = true,
        )

        Button(
            onClick = { save(Nia15LabMemoryData.addSyntheticFollowUp(memories)) },
            enabled = loadError == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add synthetic follow-up: Room C")
        }

        visible.forEach { memory ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(memory.key, style = MaterialTheme.typography.titleMedium)
                    Text(memory.content, style = MaterialTheme.typography.bodyLarge)
                    Text("ID: ${memory.id}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Receipt: ${memory.receiptStatus} · Claim: ${memory.claimStatus}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Fact: ${memory.factStatus} · Resolution: ${memory.resolutionStatus}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Capture decision: ${memory.captureDecision}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    memory.provenance.forEach { provenance ->
                        Text(
                            "Provenance: ${provenance.source ?: "not supplied"} · " +
                                "${provenance.conversationId ?: "no conversation ID"} · " +
                                "observedAt ${provenance.observedAt}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (memory.provenance.isEmpty()) {
                        Text("Provenance: [] (valid; no provenance supplied)", style = MaterialTheme.typography.bodySmall)
                    }
                    if (memory.captureDecision == "PENDING" && loadError == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { save(Nia15LabMemoryData.approve(memories, memory.id)) }) {
                                Text("Approve capture")
                            }
                            OutlinedButton(onClick = { save(Nia15LabMemoryData.reject(memories, memory.id)) }) {
                                Text("Reject capture")
                            }
                        }
                    }
                }
            }
        }

        if (visible.isEmpty() && loadError == null) {
            Text("No synthetic memories match that search.")
        }

        Text(
            "Conflict test: Room A and Room B remain separate reported claims. Approving capture does not turn either claim into a confirmed fact.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back to command centre")
        }
    }
}
