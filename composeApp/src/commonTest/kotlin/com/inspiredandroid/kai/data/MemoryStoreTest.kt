package com.inspiredandroid.kai.data

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryStoreTest {

    @Test
    fun `Manchester and London claims remain separate and unresolved`() = runTest {
        val settings = AppSettings(MapSettings())
        val store = MemoryStore(settings)

        store.store("residence", "Manchester", source = "conversation-1")
        store.store("residence", "London", source = "conversation-2")

        val claims = store.getAllMemories().sortedBy { it.createdAt }
        assertEquals(2, claims.size)
        assertEquals(listOf("Manchester", "London"), claims.map { it.content })
        assertEquals(listOf("conversation-1", "conversation-2"), claims.map { it.source })
        assertNotEquals(claims[0].id, claims[1].id)
        claims.forEach { claim ->
            assertEquals(MemoryAssessment.CONFIRMED, claim.receiptStatus)
            assertEquals(MemoryAssessment.REPORTED, claim.claimStatus)
            assertEquals(MemoryAssessment.UNKNOWN, claim.factStatus)
            assertEquals(MemoryAssessment.NEEDS_VERIFICATION, claim.resolutionStatus)
        }
    }

    @Test
    fun `exact duplicate does not create another memory or replace provenance`() = runTest {
        val store = MemoryStore(AppSettings(MapSettings()))
        val original = store.store("preferred_color", "blue", source = "first conversation")

        val duplicate = store.store("preferred_color", "blue", source = "later conversation")

        assertEquals(original.id, duplicate.id)
        assertEquals("first conversation", duplicate.source)
        assertEquals(listOf("first conversation", "later conversation"), duplicate.provenance.map { it.source })
        assertEquals(1, store.getAllMemories().size)
    }

    @Test
    fun `legacy conflicts receive distinct ids and unresolved classifications`() {
        val settings = AppSettings(MapSettings())
        settings.setMemoriesJson(
            """[{"key":"residence","content":"Manchester","createdAt":1,"updatedAt":1},{"key":"residence","content":"London","createdAt":2,"updatedAt":2}]""",
        )

        val claims = MemoryStore(settings).getAllMemories()

        assertEquals(2, claims.size)
        assertNotEquals(claims[0].id, claims[1].id)
        assertTrue(claims.all { it.resolutionStatus == MemoryAssessment.NEEDS_VERIFICATION })
        assertTrue(claims.all { it.factStatus == MemoryAssessment.UNKNOWN })
    }

    @Test
    fun `duplicate imported records are not misclassified as contradictory claims`() {
        val settings = AppSettings(MapSettings())
        settings.setMemoriesJson(
            """[{"key":"preferred_color","content":"blue","createdAt":1,"updatedAt":1},{"key":"preferred_color","content":"blue","createdAt":2,"updatedAt":2}]""",
        )

        val duplicates = MemoryStore(settings).getAllMemories()

        assertEquals(2, duplicates.size)
        assertTrue(duplicates.all { it.resolutionStatus == MemoryAssessment.UNKNOWN })
        assertNotEquals(duplicates[0].id, duplicates[1].id)
    }

    @Test
    fun `changed preference and user correction preserve both claims`() = runTest {
        val store = MemoryStore(AppSettings(MapSettings()))
        store.store("preferred_editor", "Vim", category = MemoryCategory.PREFERENCE, source = "reported preference")
        store.store("preferred_editor", "VS Code", category = MemoryCategory.PREFERENCE, source = "user correction")

        val claims = store.getAllMemories()
        assertEquals(setOf("Vim", "VS Code"), claims.map { it.content }.toSet())
        assertTrue(claims.all { it.category == MemoryCategory.PREFERENCE })
        assertTrue(claims.all { it.resolutionStatus == MemoryAssessment.NEEDS_VERIFICATION })
        assertEquals(setOf("reported preference", "user correction"), claims.map { it.source }.toSet())
    }

    @Test
    fun `competing dates remain separate and unresolved`() = runTest {
        val store = MemoryStore(AppSettings(MapSettings()))
        store.store("project_deadline", "2026-07-12", source = "meeting notes")
        store.store("project_deadline", "2026-08-03", source = "later message")

        val claims = store.getAllMemories()
        assertEquals(setOf("2026-07-12", "2026-08-03"), claims.map { it.content }.toSet())
        assertEquals(setOf("meeting notes", "later message"), claims.map { it.source }.toSet())
        assertTrue(claims.all { it.resolutionStatus == MemoryAssessment.NEEDS_VERIFICATION })
    }

    @Test
    fun `persisted data survives a fresh store and retains provenance`() = runTest {
        val settings = MapSettings()
        val firstStore = MemoryStore(AppSettings(settings))
        val saved = firstStore.store(
            "project",
            "NIA14",
            source = "conversation",
            sourceConversationId = "conversation-42",
        )

        val restored = MemoryStore(AppSettings(settings)).getAllMemories().single()

        assertEquals(saved, restored)
        assertEquals("conversation", restored.source)
        assertEquals("conversation-42", restored.sourceConversationId)
        assertEquals(saved.provenance, restored.provenance)
    }

    @Test
    fun `memories without provenance remain explicitly un-attributed`() = runTest {
        val store = MemoryStore(AppSettings(MapSettings()))
        val entry = store.store("timezone", "UTC")

        assertNull(entry.source)
        assertNull(entry.sourceConversationId)
        assertTrue(entry.provenance.isEmpty())
        assertEquals(MemoryAssessment.REPORTED, entry.claimStatus)
        assertEquals(MemoryAssessment.UNKNOWN, entry.factStatus)
    }

    @Test
    fun `conflicting values cannot be silently forgotten by a shared key`() = runTest {
        val store = MemoryStore(AppSettings(MapSettings()))
        store.store("residence", "Manchester")
        val otherClaim = store.store("residence", "London")

        assertFalse(store.forget("residence"))
        assertTrue(store.forgetById(otherClaim.id))
        assertEquals(listOf("Manchester"), store.getAllMemories().map { it.content })
    }

    @Test
    fun `persistence failure is surfaced and does not report a successful write`() = runTest {
        val settings = object : Settings by MapSettings() {
            override fun putString(key: String, value: String): Unit = throw IllegalStateException("storage unavailable")
        }
        val store = MemoryStore(AppSettings(settings))

        val error = assertFailsWith<IllegalStateException> {
            store.store("important", "retain this")
        }

        assertEquals("storage unavailable", error.message)
        assertTrue(store.getAllMemories().isEmpty())
    }
}
