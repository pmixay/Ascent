package com.pmixay.ascent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator

class ConversationStoreTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val now = 1_800_000_000_000L
    private fun conversation(id: String = "conversation-1", start: Long = now - 60_000) = SavedConversation(
        id, start, 30_000, listOf(TranscriptSegment("speaker_1", 100, 700, "Секретный разговор")),
        mapOf("speaker_1" to "Анна"),
    )
    private fun root() = Files.createTempDirectory("conversation-store").toFile()

    @Test fun encryptedHistoryRoundTripsMultipleConversationsWithoutPlaintextFiles() {
        val root = root()
        try {
            val store = ConversationStore(root, { key }, { now })
            val input = listOf(conversation(), conversation("conversation-2", now - 30_000))
            assertEquals(input.reversed(), store.save(input))
            assertEquals(input.reversed(), store.load())
            assertEquals(listOf("conversations.enc"), root.list()!!.toList())
            assertFalse(File(root, "conversations.enc").readBytes().toString(Charsets.UTF_8).contains("Секретный"))
        } finally { root.deleteRecursively() }
    }

    @Test fun freshNonceChangesCiphertextAndTamperingIsRejected() {
        val root = root()
        try {
            val file = File(root, "history.enc")
            val store = EncryptedJsonFile(file) { key }
            val json = JSONObject().put("text", "Секрет")
            store.write(json)
            val first = file.readBytes()
            store.write(json)
            assertFalse(first.contentEquals(file.readBytes()))
            val corrupt = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(corrupt)
            assertThrows(Exception::class.java) { store.read() }
        } finally { root.deleteRecursively() }
    }

    @Test fun encryptedFilesCannotBeSwappedAcrossStores() {
        val root = root()
        try {
            val first = File(root, "conversations.enc")
            EncryptedJsonFile(first) { key }.write(JSONObject().put("value", "private"))
            val second = File(root, "voices.enc")
            first.copyTo(second)
            assertThrows(Exception::class.java) { EncryptedJsonFile(second) { key }.read() }
        } finally { root.deleteRecursively() }
    }

    @Test fun expiryUsesCaptureTimeAndRenamingCannotExtendRetention() {
        val root = root()
        try {
            var clock = now
            val store = ConversationStore(root, { key }, { clock })
            val original = conversation(start = now - HISTORY_RETENTION_MS + 10)
            store.save(listOf(original))
            store.save(listOf(original.copy(speakerNames = mapOf("speaker_1" to "Новое имя"))))
            clock += 10
            assertTrue(store.load().isEmpty())
            assertTrue(store.load().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun migratesOldPlaintextBeforeRemovingItAndKeepsCaptureTimestamp() {
        val root = root()
        try {
            val original = conversation()
            val legacy = File(root, "latest-transcript.json")
            legacy.writeText(ConversationStore.conversationJson(original)
                .put("audioFile", "conversation-${original.startedAtMs}.wav").toString())
            val store = ConversationStore(root, { key }, { now })
            val migrated = store.load().single()
            assertEquals(original.startedAtMs, migrated.startedAtMs)
            assertEquals(original.segments, migrated.segments)
            assertEquals(original.speakerNames, migrated.speakerNames)
            assertFalse(legacy.exists())
            assertEquals(listOf(migrated), store.load())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedEncryptionDoesNotDeleteLegacyTranscript() {
        val root = root()
        try {
            val legacy = File(root, "latest-transcript.json")
            legacy.writeText(ConversationStore.conversationJson(conversation())
                .put("audioFile", "conversation-${now - 60_000}.wav").toString())
            assertThrows(IllegalStateException::class.java) {
                ConversationStore(root, { error("Key unavailable") }, { now }).load()
            }
            assertTrue(legacy.isFile)
            assertFalse(File(root, "conversations.enc").exists())
        } finally { root.deleteRecursively() }
    }
}
