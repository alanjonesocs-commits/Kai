package com.inspiredandroid.kai.diagnostics

import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
data class NiaDiagnosticEvent(
    val id: String,
    val timestamp: String,
    val severity: String,
    val component: String,
    val operation: String,
    val message: String,
    val exceptionType: String? = null,
    val traceId: String,
)

object NiaDiagnostics {
    private const val STORAGE_KEY = "nia_diagnostics_events_v1"
    private const val MAX_EVENTS = 200
    private val settings by lazy { Settings() }
    private val json = Json { ignoreUnknownKeys = true }

    fun record(
        severity: String,
        component: String,
        operation: String,
        message: String,
        exceptionType: String? = null,
        traceId: String = createTraceId(),
    ): NiaDiagnosticEvent {
        val event = NiaDiagnosticEvent(
            id = createTraceId(),
            timestamp = Clock.System.now().toString(),
            severity = severity.uppercase().take(16),
            component = component.take(80),
            operation = operation.take(100),
            message = redact(message).take(1000),
            exceptionType = exceptionType?.take(160),
            traceId = traceId,
        )
        val updated = (readAll() + event).takeLast(MAX_EVENTS)
        settings.putString(STORAGE_KEY, json.encodeToString(updated))
        return event
    }

    fun readAll(): List<NiaDiagnosticEvent> {
        val stored = settings.getStringOrNull(STORAGE_KEY) ?: return emptyList()
        return runCatching { json.decodeFromString<List<NiaDiagnosticEvent>>(stored) }
            .getOrDefault(emptyList())
    }

    fun clear() {
        settings.remove(STORAGE_KEY)
    }

    private fun redact(input: String): String {
        var safe = input
        safe = Regex("(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)(\\s*[=:]\\s*)[^\\s,;]+")
            .replace(safe) { match -> "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]" }
        safe = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*").replace(safe, "Bearer [REDACTED]")
        safe = Regex("(?i)sk-[A-Za-z0-9_-]{12,}").replace(safe, "[REDACTED]")
        return safe
    }

    private fun createTraceId(): String =
        "${Clock.System.now().toEpochMilliseconds()}-${(0..999999).random()}"
}
