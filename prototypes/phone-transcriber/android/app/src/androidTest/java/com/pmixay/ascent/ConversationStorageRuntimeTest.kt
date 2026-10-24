package com.pmixay.ascent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConversationStorageRuntimeTest {
    @Test fun androidKeystoreHistorySurvivesStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "history-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val conversation = SavedConversation("test", System.currentTimeMillis() - 1_000, 500,
                listOf(TranscriptSegment("speaker_1", 0, 500, "Тестовая расшифровка")),
                mapOf("speaker_1" to "Участник 1"))
            ConversationStore(root).save(listOf(conversation))
            assertEquals(listOf(conversation), ConversationStore(root).load())
            assertFalse(File(root, "conversations.enc").readText().contains("Тестовая расшифровка"))
        } finally { root.deleteRecursively() }
    }
}
