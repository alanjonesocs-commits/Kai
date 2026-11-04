package com.inspiredandroid.kai.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Nia15MemoryLabTest {

    @Test
    fun `fictional conflicting claims are preserved independently`() {
        val memories = Nia15LabMemoryData.seed()
        val roomClaims = memories.filter { it.key == "workspace_room" }

        assertEquals(2, roomClaims.size)
        assertTrue(roomClaims.all { it.claimStatus == "REPORTED" })
        assertTrue(roomClaims.all { it.factStatus == "UNKNOWN" })
        assertTrue(roomClaims.all { it.resolutionStatus == "NEEDS_VERIFICATION" })
        assertEquals(2, roomClaims.map { it.id }.distinct().size)
    }

    @Test
    fun `approving capture does not promote a reported claim to fact`() {
        val memories = Nia15LabMemoryData.seed()
        val approved = Nia15LabMemoryData.approve(memories, "synthetic-001")
        val item = approved.first { it.id == "synthetic-001" }

        assertEquals("APPROVED", item.captureDecision)
        assertEquals("REPORTED", item.claimStatus)
        assertEquals("UNKNOWN", item.factStatus)
        assertEquals("NEEDS_VERIFICATION", item.resolutionStatus)
    }

    @Test
    fun `new follow-up appends without overwriting earlier claims`() {
        val original = Nia15LabMemoryData.seed()
        val updated = Nia15LabMemoryData.addSyntheticFollowUp(original)

        assertEquals(original.size + 1, updated.size)
        assertEquals(original, updated.take(original.size))
        assertTrue(updated.last().content.contains("Room C"))
    }

    @Test
    fun `synthetic store survives encode and decode with empty provenance allowed`() {
        val memories = Nia15LabMemoryData.seed().mapIndexed { index, memory ->
            if (index == 0) memory.copy(provenance = emptyList()) else memory
        }

        assertEquals(memories, Nia15LabMemoryData.decode(Nia15LabMemoryData.encode(memories)))
    }

    @Test
    fun `duplicate synthetic IDs are rejected`() {
        val memories = Nia15LabMemoryData.seed()
        val duplicate = memories + memories.first()

        assertFailsWith<IllegalArgumentException> {
            Nia15LabMemoryData.encode(duplicate)
        }
    }
}
