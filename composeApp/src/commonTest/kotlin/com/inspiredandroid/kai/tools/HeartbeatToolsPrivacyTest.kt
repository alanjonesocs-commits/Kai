package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.MemoryStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeartbeatToolsPrivacyTest {
    @Test
    fun localOnlyMemoryCannotBePromotedIntoProviderVisibleSoul() = runTest {
        val settings = AppSettings(MapSettings())
        val memories = MemoryStore(settings)
        val memory = memories.store("family_note", "A private family detail")
        val tool = HeartbeatTools.promoteLearningTool(memories, settings)

        val result = tool.execute(
            mapOf(
                "memory_key" to memory.key,
                "soul_addition" to "A private family detail",
            ),
        ) as Map<*, *>

        assertEquals(false, result["success"])
        assertTrue(memories.getAllMemories().any { it.id == memory.id })
        assertEquals("", settings.getSoulText())
    }

    @Test
    fun explicitlyShareableMemoryMayBePromoted() = runTest {
        val settings = AppSettings(MapSettings())
        val memories = MemoryStore(settings)
        val memory = memories.store("general_preference", "Prefers concise answers")
        memories.updateProviderShareById(memory.id, true)
        val tool = HeartbeatTools.promoteLearningTool(memories, settings)

        val result = tool.execute(
            mapOf(
                "memory_key" to memory.key,
                "soul_addition" to "Keep answers concise.",
            ),
        ) as Map<*, *>

        assertEquals(true, result["success"])
        assertFalse(memories.getAllMemories().any { it.id == memory.id })
        assertTrue(settings.getSoulText().contains("Keep answers concise."))
    }
}
