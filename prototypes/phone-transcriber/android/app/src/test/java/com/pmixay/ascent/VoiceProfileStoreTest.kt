package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.math.sqrt

class VoiceProfileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val now = 2_000_000_000_000L
    private val captured = now - 60_000

    @Test fun voiceIsLearnedOnlyAfterExplicitNamingAndSurvivesRestartEncrypted() {
        val root = temporary.newFolder()
        val store = VoiceProfileStore(root) { key }
        assertTrue(store.match("first", captured, listOf(voice("s1", 1f, 0f)), emptySet(), now).isEmpty())
        assertFalse(File(root, "voice-profiles.enc").exists())
        assertTrue(store.remember("first", "s1", "Анна", now))
        val encrypted = File(root, "voice-profiles.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(encrypted.contains("conversationId"))
        assertFalse(encrypted.contains("vector"))

        val restarted = VoiceProfileStore(root) { key }
        assertEquals(mapOf("s2" to "Анна"), restarted.match("next", now,
            listOf(voice("s2", 0.99f, 0.02f)), setOf("first"), now))
        // Unconfirmed embeddings are not persisted even when another profile exists.
        assertFalse(restarted.remember("first", "s1", "Анна", now))
    }

    @Test fun shortMixedOrInvalidSpeechCannotEnrollOrRecallAName() {
        val store = VoiceProfileStore(temporary.newFolder()) { key }
        val good = voice("s1", 1f, 0f)
        val bad = listOf(good.copy(speechMs = 5999), good.copy(windowCount = 2), good.copy(cohesion = 0.79f),
            good.copy(cohesion = Float.NaN), good.copy(vector = FloatArray(192)),
            good.copy(vector = floatArrayOf(1f, 0f)), good.copy(speakerId = "speaker_unknown"))
        bad.forEachIndexed { index, signature ->
            val id = "bad-$index"
            assertTrue(store.match(id, captured, listOf(signature), emptySet(), now).isEmpty())
            assertFalse(store.remember(id, signature.speakerId, "Анна", now))
        }
    }

    @Test fun similarEnrolledVoicesRequireClearRunnerUpMargin() {
        val store = VoiceProfileStore(temporary.newFolder()) { key }
        store.match("a", captured, listOf(voice("a", 1f, 0f)), emptySet(), now)
        store.remember("a", "a", "Анна", now)
        // Explicitly naming a conflicting guess creates a separate profile, not an automatic merge.
        store.match("b", captured, listOf(voice("b", 0.9f, sqrt(0.19f))), setOf("a"), now)
        store.remember("b", "b", "Борис", now)
        assertTrue(store.match("query", now, listOf(voice("q", 0.975f, 0.222f)), setOf("a", "b"), now).isEmpty())
    }

    @Test fun twoDistinctSessionSpeakersCannotBothReceiveOneRememberedIdentity() {
        val store = VoiceProfileStore(temporary.newFolder()) { key }
        store.match("a", captured, listOf(voice("a", 1f, 0f)), emptySet(), now)
        store.remember("a", "a", "Анна", now)
        assertTrue(store.match("mixed", now,
            listOf(voice("one", 1f, 0f), voice("two", 0.99f, 0.02f)), setOf("a"), now).isEmpty())
    }

    @Test fun automaticPredictionsNeverChangeAnEnrolledTemplate() {
        val root = temporary.newFolder()
        val store = VoiceProfileStore(root) { key }
        store.match("a", captured, listOf(voice("a", 1f, 0f)), emptySet(), now)
        store.remember("a", "a", "Анна", now)
        val before = File(root, "voice-profiles.enc").readBytes()
        repeat(20) { index ->
            assertEquals(mapOf("q" to "Анна"), store.match("q$index", now,
                listOf(voice("q", 0.82f, sqrt(1f - 0.82f * 0.82f))), setOf("a"), now))
        }
        assertArrayEquals(before, File(root, "voice-profiles.enc").readBytes())
        assertEquals(mapOf("return" to "Анна"), store.match("return", now,
            listOf(voice("return", 1f, 0f)), setOf("a"), now))
    }

    @Test fun removingSourceConversationRemovesLearnedContributionAfterRestart() {
        val root = temporary.newFolder()
        val store = VoiceProfileStore(root) { key }
        store.match("a", captured, listOf(voice("a", 1f, 0f)), emptySet(), now)
        store.remember("a", "a", "Анна", now)
        store.prune(emptySet(), now)
        assertFalse(store.remember("a", "a", "Анна", now))
        val restarted = VoiceProfileStore(root) { key }
        assertTrue(restarted.match("q", now, listOf(voice("q", 1f, 0f)), setOf("a"), now).isEmpty())
    }

    @Test fun confirmedNewConversationLearnsItsVoiceWithoutRetainingDeletedSourceContribution() {
        val root = temporary.newFolder()
        val store = VoiceProfileStore(root) { key }
        store.match("a", captured, listOf(voice("a", 1f, 0f)), emptySet(), now)
        store.remember("a", "a", "Анна", now)
        val changedVoice = voice("b", 0.82f, sqrt(1f - 0.82f * 0.82f))
        assertEquals(mapOf("b" to "Анна"), store.match("b", now, listOf(changedVoice), setOf("a"), now))
        assertTrue(store.remember("b", "b", "Анна", now))
        store.prune(setOf("b"), now)
        assertEquals(mapOf("b" to "Анна"), VoiceProfileStore(root) { key }
            .match("c", now, listOf(changedVoice), setOf("b"), now))
    }

    @Test fun sevenDayExpiryUsesOriginalCaptureTimeNotProfileUpdateTime() {
        val root = temporary.newFolder()
        val store = VoiceProfileStore(root) { key }
        val oldCapture = now - HISTORY_RETENTION_MS + 1
        store.match("old", oldCapture, listOf(voice("s", 1f, 0f)), emptySet(), now)
        assertTrue(store.remember("old", "s", "Анна", now))
        store.prune(setOf("old"), now + 1)
        assertTrue(VoiceProfileStore(root) { key }.match("new", now + 1,
            listOf(voice("s", 1f, 0f)), setOf("old"), now + 1).isEmpty())
    }

    @Test fun failedWriteDoesNotLearnPhantomProfile() {
        val memory = VoiceMemory()
        memory.match("a", captured, listOf(voice("a", 1f, 0f)), emptySet(), now)
        try {
            memory.remember("a", "a", "Анна", now) { throw java.io.IOException("disk full") }
            fail("Expected persistence failure")
        } catch (_: java.io.IOException) { }
        assertTrue(memory.match("next", now, listOf(voice("next", 1f, 0f)), setOf("a"), now).isEmpty())
    }

    @Test fun discardedOrFutureSessionsCannotTeachVoiceMemory() {
        val memory = VoiceMemory()
        memory.match("a", captured, listOf(voice("a", 1f, 0f)), emptySet(), now)
        memory.discard("a")
        assertFalse(memory.remember("a", "a", "Анна", now))
        memory.match("future", now + 1, listOf(voice("a", 1f, 0f)), emptySet(), now)
        assertFalse(memory.remember("future", "a", "Анна", now))
    }

    private fun voice(id: String, first: Float, second: Float): VoiceSignature = VoiceSignature(id,
        FloatArray(192).apply { this[0] = first; this[1] = second }, 9000, 4, 0.95f)
}
