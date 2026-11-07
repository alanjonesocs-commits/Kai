package com.inspiredandroid.kai.nia.retrieval

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.MemoryEntry
import com.inspiredandroid.kai.data.RawMemoryJsonReadResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

/**
 * Schema 1.1.0 snapshot encoder. This is an explicit local snapshot primitive:
 * it never transfers, activates, migrates, repairs, or writes canonical memory.
 */
internal object NiaMemorySnapshotBuilder {
    private const val SCHEMA_VERSION = "1.1.0"
    private const val HASH_SCOPE = "snapshot-envelope-v1"
    private const val MAX_BYTES = 32 * 1024 * 1024 // Provisional until Pixel 9 heap measurements.
    private const val MAX_DEPTH = 32
    private val snapshotIdPattern = Regex("^[A-Za-z0-9._-]{1,128}$")

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
        allowSpecialFloatingPointValues = false
    }
    private val validationJson = Json { ignoreUnknownKeys = true; coerceInputValues = false }

    private val requiredRecordFields = setOf(
        "key", "content", "createdAt", "updatedAt", "category", "hitCount",
        "source", "id", "receiptStatus", "claimStatus", "factStatus",
        "resolutionStatus", "sourceConversationId", "provenance",
    )
    private val requiredProvenanceFields = setOf("source", "conversationId", "observedAt")

    fun buildFromAppSettings(
        appSettings: AppSettings,
        snapshotId: String = UUID.randomUUID().toString(),
        createdAtEpochMs: Long = System.currentTimeMillis(),
    ): String = when (val result = appSettings.readMemoriesJsonForSnapshot()) {
        RawMemoryJsonReadResult.Missing -> throw IllegalStateException("MEMORY_STORE_MISSING")
        is RawMemoryJsonReadResult.Unreadable -> throw IllegalStateException(result.reasonCode)
        is RawMemoryJsonReadResult.Present -> buildFromRawJson(
            result.rawJson, snapshotId, createdAtEpochMs,
        )
    }

    fun buildFromRawJson(
        rawMemoryJson: String,
        snapshotId: String = UUID.randomUUID().toString(),
        createdAtEpochMs: Long = System.currentTimeMillis(),
        sourceApplication: String = "NIA15",
        sourcePlatform: String = "Android",
        sourceDeviceLabel: String? = null,
        sourceSystem: String = "NIA15",
        sourceGeneration: Long? = null,
        generationAuthority: String? = null,
    ): String {
        require(rawMemoryJson.isNotBlank()) { "MEMORY_JSON_BLANK" }
        StrictJsonPreflight(rawMemoryJson).validate()
        val records = try {
            Json.parseToJsonElement(rawMemoryJson) as? JsonArray
                ?: throw IllegalArgumentException("MEMORY_JSON_NOT_ARRAY")
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("MEMORY_JSON_INVALID", error)
        }
        validateRecords(records)
        return buildEnvelope(
            canonicalElement(records) as JsonArray,
            snapshotId,
            createdAtEpochMs,
            sourceApplication,
            sourcePlatform,
            sourceDeviceLabel,
            sourceSystem,
            sourceGeneration,
            generationAuthority,
        )
    }

    fun build(
        records: List<MemoryEntry>,
        snapshotId: String = UUID.randomUUID().toString(),
        createdAtEpochMs: Long = System.currentTimeMillis(),
        sourceApplication: String = "NIA15",
        sourcePlatform: String = "Android",
        sourceDeviceLabel: String? = null,
        sourceSystem: String = "NIA15",
        sourceGeneration: Long? = null,
        generationAuthority: String? = null,
    ): String {
        require(records.all { it.id.isNotBlank() && it.key.isNotBlank() }) { "RECORD_ID_OR_KEY_INVALID" }
        require(records.map { it.id }.distinct().size == records.size) { "DUPLICATE_RECORD_ID" }
        require(records.all {
            it.createdAt >= 0 && it.updatedAt >= 0 && it.hitCount >= 0 &&
                it.provenance.all { p -> p.observedAt >= 0 }
        }) { "RECORD_NUMBER_INVALID" }
        val encoded = json.encodeToJsonElement(ListSerializer(MemoryEntry.serializer()), records)
        return buildEnvelope(
            canonicalElement(encoded) as JsonArray,
            snapshotId,
            createdAtEpochMs,
            sourceApplication,
            sourcePlatform,
            sourceDeviceLabel,
            sourceSystem,
            sourceGeneration,
            generationAuthority,
        )
    }

    private fun validateRecords(records: JsonArray) {
        val ids = mutableSetOf<String>()
        records.forEachIndexed { index, element ->
            val record = element as? JsonObject ?: error("RECORD_NOT_OBJECT:$index")
            require(requiredRecordFields.all { it in record.keys }) { "RECORD_FIELDS_INVALID:$index" }
            val decoded = try {
                validationJson.decodeFromJsonElement(MemoryEntry.serializer(), record)
            } catch (error: Exception) {
                throw IllegalArgumentException("RECORD_INVALID:$index", error)
            }
            require(decoded.key.isNotBlank() && decoded.id.isNotBlank()) { "RECORD_ID_OR_KEY_INVALID:$index" }
            require(ids.add(decoded.id)) { "DUPLICATE_RECORD_ID:$index" }
            require(
                decoded.createdAt >= 0 && decoded.updatedAt >= 0 && decoded.hitCount >= 0 &&
                    decoded.provenance.all { it.observedAt >= 0 },
            ) { "RECORD_NUMBER_INVALID:$index" }
            decoded.provenance.forEachIndexed { provenanceIndex, _ ->
                val raw = (record["provenance"] as JsonArray)[provenanceIndex] as? JsonObject
                    ?: error("PROVENANCE_NOT_OBJECT:$index:$provenanceIndex")
                require(requiredProvenanceFields.all { it in raw.keys }) {
                    "PROVENANCE_FIELDS_INVALID:$index:$provenanceIndex"
                }
            }
        }
    }

    private fun buildEnvelope(
        records: JsonArray,
        snapshotId: String,
        createdAtEpochMs: Long,
        sourceApplication: String,
        sourcePlatform: String,
        sourceDeviceLabel: String?,
        sourceSystem: String,
        sourceGeneration: Long?,
        generationAuthority: String?,
    ): String {
        require(snapshotIdPattern.matches(snapshotId)) { "SNAPSHOT_ID_INVALID" }
        require(createdAtEpochMs >= 0) { "CREATED_AT_INVALID" }
        require(sourceGeneration == null || sourceGeneration >= 0) { "SOURCE_GENERATION_INVALID" }
        require(generationAuthority == null || generationAuthority.isNotBlank()) { "GENERATION_AUTHORITY_INVALID" }
        require(sourceApplication.isNotBlank() && sourcePlatform.isNotBlank() && sourceSystem.isNotBlank()) {
            "SOURCE_VALUE_INVALID"
        }

        val recordsHash = sha256(canonicalBytes(records))
        val unsigned = buildJsonObject {
            put("schema_version", SCHEMA_VERSION)
            put("snapshot_id", snapshotId)
            put("created_at_epoch_ms", createdAtEpochMs)
            put("source", buildJsonObject {
                put("application", sourceApplication)
                put("platform", sourcePlatform)
                if (sourceDeviceLabel == null) put("device_label", JsonNull) else put("device_label", sourceDeviceLabel)
                put("system", sourceSystem)
            })
            if (sourceGeneration == null) put("source_generation", JsonNull) else put("source_generation", sourceGeneration)
            if (generationAuthority == null) put("generation_authority", JsonNull) else put("generation_authority", generationAuthority)
            put("record_count", records.size)
            put("records", records)
            put("records_hash", recordsHash)
            put("hash_scope", HASH_SCOPE)
        }
        val envelopeHash = sha256(canonicalBytes(unsigned))
        val completeEnvelope = canonicalBytes(JsonObject(unsigned + ("envelope_hash" to JsonPrimitive(envelopeHash))))
        require(completeEnvelope.size <= MAX_BYTES) { "SNAPSHOT_SIZE_INVALID" }
        return completeEnvelope.decodeToString()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun canonicalBytes(element: JsonElement): ByteArray = canonicalString(element).encodeToByteArray()

    private fun canonicalElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonicalElement(it.value) })
        is JsonArray -> JsonArray(element.map(::canonicalElement))
        else -> element
    }

    /** NIA-CJ1 canonical JSON: UTF-16 key ordering, compact UTF-8, strict escaping and int64 only. */
    private fun canonicalString(element: JsonElement): String = buildString { appendValue(element) }

    private fun StringBuilder.appendValue(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                append('{')
                element.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    appendQuoted(key)
                    append(':')
                    appendValue(element.getValue(key))
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    appendQuoted(element.content)
                } else {
                    val token = element.content
                    when (token) {
                        "true", "false", "null" -> append(token)
                        else -> {
                            require(token != "-0" && token.matches(Regex("^(0|-[1-9][0-9]*|[1-9][0-9]*)$"))) {
                                "UNSUPPORTED_NUMBER"
                            }
                            require(token.toLongOrNull() != null) { "INTEGER_OUT_OF_RANGE" }
                            append(token)
                        }
                    }
                }
            }
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\b' -> append("\\b")
                char == '\t' -> append("\\t")
                char == '\n' -> append("\\n")
                char == '\u000c' -> append("\\f")
                char == '\r' -> append("\\r")
                char in '\uD800'..'\uDBFF' -> {
                    require(index + 1 < value.length && value[index + 1] in '\uDC00'..'\uDFFF') { "LONE_SURROGATE" }
                    append(char).append(value[index + 1])
                    index++
                }
                char in '\uDC00'..'\uDFFF' -> throw IllegalArgumentException("LONE_SURROGATE")
                char.code <= 0x1f -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
            index++
        }
        append('"')
    }

    private fun utf8LengthAtMost(value: String): Int {
        var total = 0
        var index = 0
        while (index < value.length) {
            val char = value[index]
            total += when {
                char in '\uD800'..'\uDBFF' -> {
                    require(index + 1 < value.length && value[index + 1] in '\uDC00'..'\uDFFF') { "LONE_SURROGATE" }
                    index++
                    4
                }
                char in '\uDC00'..'\uDFFF' -> throw IllegalArgumentException("LONE_SURROGATE")
                char.code <= 0x7f -> 1
                char.code <= 0x7ff -> 2
                else -> 3
            }
            require(total <= MAX_BYTES) { "SNAPSHOT_SIZE_INVALID" }
            index++
        }
        return total
    }

    /**
     * Strict raw-text parser. It rejects duplicate decoded names before a JSON
     * object/map is created, including keys inside unknown future record fields.
     */
    private class StrictJsonPreflight(private val input: String) {
        private var position = 0

        fun validate() {
            utf8LengthAtMost(input)
            whitespace()
            // The raw records array sits inside the envelope object (depth 1).
            value(1)
            whitespace()
            require(position == input.length) { "TRAILING_JSON_DATA" }
        }

        private fun value(depth: Int) {
            require(depth <= MAX_DEPTH) { "JSON_DEPTH_EXCEEDED" }
            whitespace()
            require(position < input.length) { "JSON_VALUE_MISSING" }
            when (input[position]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> integer()
                else -> error("JSON_TOKEN_INVALID")
            }
        }

        private fun objectValue(depth: Int) {
            require(depth <= MAX_DEPTH) { "JSON_DEPTH_EXCEEDED" }
            expect('{')
            whitespace()
            if (consume('}')) return
            val names = mutableSetOf<String>()
            while (true) {
                whitespace()
                require(position < input.length && input[position] == '"') { "JSON_KEY_INVALID" }
                val name = stringValue()
                require(names.add(name)) { "DUPLICATE_OBJECT_KEY" }
                whitespace()
                expect(':')
                value(depth)
                whitespace()
                if (consume('}')) return
                expect(',')
            }
        }

        private fun arrayValue(depth: Int) {
            require(depth <= MAX_DEPTH) { "JSON_DEPTH_EXCEEDED" }
            expect('[')
            whitespace()
            if (consume(']')) return
            while (true) {
                value(depth)
                whitespace()
                if (consume(']')) return
                expect(',')
            }
        }

        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (position < input.length) {
                val char = input[position++]
                when {
                    char == '"' -> return result.toString()
                    char == '\\' -> {
                        require(position < input.length) { "JSON_ESCAPE_INVALID" }
                        when (val escaped = input[position++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> unicodeEscape(result)
                            else -> error("JSON_ESCAPE_INVALID")
                        }
                    }
                    char.code < 0x20 -> error("JSON_CONTROL_CHARACTER")
                    char in '\uD800'..'\uDBFF' -> {
                        require(position < input.length && input[position] in '\uDC00'..'\uDFFF') { "LONE_SURROGATE" }
                        result.append(char).append(input[position++])
                    }
                    char in '\uDC00'..'\uDFFF' -> error("LONE_SURROGATE")
                    else -> result.append(char)
                }
            }
            error("JSON_STRING_UNTERMINATED")
        }

        private fun unicodeEscape(result: StringBuilder) {
            val first = hexCodeUnit()
            when {
                first in 0xd800..0xdbff -> {
                    require(position + 1 < input.length && input[position] == '\\' && input[position + 1] == 'u') {
                        "LONE_SURROGATE"
                    }
                    position += 2
                    val second = hexCodeUnit()
                    require(second in 0xdc00..0xdfff) { "LONE_SURROGATE" }
                    result.append(first.toChar()).append(second.toChar())
                }
                first in 0xdc00..0xdfff -> error("LONE_SURROGATE")
                else -> result.append(first.toChar())
            }
        }

        private fun hexCodeUnit(): Int {
            require(position + 4 <= input.length) { "JSON_UNICODE_ESCAPE_INVALID" }
            val token = input.substring(position, position + 4)
            require(token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "JSON_UNICODE_ESCAPE_INVALID"
            }
            position += 4
            return token.toInt(16)
        }

        private fun integer() {
            val start = position
            if (consume('-')) require(position < input.length) { "JSON_NUMBER_INVALID" }
            if (consume('0')) {
                require(position >= input.length || input[position] !in '0'..'9') { "JSON_NUMBER_INVALID" }
            } else {
                require(position < input.length && input[position] in '1'..'9') { "JSON_NUMBER_INVALID" }
                while (position < input.length && input[position] in '0'..'9') position++
            }
            val token = input.substring(start, position)
            require(token != "-0") { "INVALID_NUMBER" }
            require(position >= input.length || input[position] !in ".eE") { "UNSUPPORTED_DECIMAL" }
            require(token.toLongOrNull() != null) { "INTEGER_OUT_OF_RANGE" }
        }

        private fun literal(text: String) {
            require(input.regionMatches(position, text, 0, text.length)) { "JSON_LITERAL_INVALID" }
            position += text.length
        }

        private fun whitespace() {
            while (position < input.length && input[position] in charArrayOf(' ', '\t', '\r', '\n')) position++
        }

        private fun expect(char: Char) {
            require(position < input.length && input[position] == char) { "JSON_STRUCTURE_INVALID" }
            position++
        }

        private fun consume(char: Char): Boolean {
            if (position < input.length && input[position] == char) {
                position++
                return true
            }
            return false
        }
    }
}
