package com.inspiredandroid.kai.data

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryApprovalStoreTest {
    @Test
    fun suggestionIsPersistedPendingButNotAddedToPermanentMemoryUntilApproved() = runTest {
        val settings = AppSettings(MapSettings())
        val approvals = MemoryApprovalStore(settings)
        val memories = MemoryStore(settings)
        val proposal = approvals.suggest(
            key = "preferred_editor",
            content = "Vim",
            category = MemoryCategory.PREFERENCE,
            source = "user correction",
        )

        assertEquals(1, approvals.getPendingApprovals().size)
        assertTrue(approvals.getPendingApprovals().single().isSensitive)
        assertTrue(memories.getAllMemories().isEmpty())

        val approved = assertNotNull(approvals.approve(proposal.id, memories))
        assertEquals("Vim", approved.content)
        assertFalse(approved.providerShareAllowed)
        assertTrue(approvals.getPendingApprovals().isEmpty())
        assertEquals(listOf(approved), memories.getAllMemories())
    }

    @Test
    fun rejectingProposalNeverWritesPermanentMemory() = runTest {
        val settings = AppSettings(MapSettings())
        val approvals = MemoryApprovalStore(settings)
        val memories = MemoryStore(settings)
        val proposal = approvals.suggest("health_note", "Sensitive local note")

        assertTrue(approvals.reject(proposal.id))
        assertFalse(approvals.reject(proposal.id))
        assertTrue(approvals.getPendingApprovals().isEmpty())
        assertTrue(memories.getAllMemories().isEmpty())
    }

    @Test
    fun proposalsSurviveRecreatingTheStore() = runTest {
        val settings = AppSettings(MapSettings())
        val proposal = MemoryApprovalStore(settings).suggest("project", "NIA15")

        val restored = MemoryApprovalStore(settings).getPendingApprovals().single()
        assertEquals(proposal, restored)
    }
}
