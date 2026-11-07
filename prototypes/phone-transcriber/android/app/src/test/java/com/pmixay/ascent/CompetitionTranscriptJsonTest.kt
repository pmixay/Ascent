package com.pmixay.ascent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CompetitionTranscriptJsonTest {
    private fun conversation(segments: List<TranscriptSegment>, durationMs: Long = 5_000) = SavedConversation(
        id = "file-1", startedAtMs = 1_800_000_000_000, durationMs = durationMs,
        segments = segments, speakerNames = mapOf("speaker_1" to "Анна"),
    )

    @Test fun exportsOnlyJuryFieldsWithOriginalTimingAndStableAnonymousIds() {
        val source = conversation(listOf(
            TranscriptSegment("speaker_2", 2_000, 3_000, "Да, конечно"),
            TranscriptSegment("speaker_1", 100, 1_000, "Он сказал: \"привет\"\nснова"),
            TranscriptSegment("speaker_1", 800, 1_500, "Перекрытие"),
        ))
        val json = JSONObject(CompetitionTranscriptJson.serialize(source))
        assertEquals(setOf("transcript"), json.keys().asSequence().toSet())
        val turns = json.getJSONArray("transcript")
        assertEquals(3, turns.length())
        assertEquals(listOf(100L, 800L, 2_000L), (0 until turns.length()).map { turns.getJSONObject(it).getLong("start_ms") })
        for (index in 0 until turns.length()) {
            assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"),
                turns.getJSONObject(index).keys().asSequence().toSet())
        }
        assertEquals("speaker_1", turns.getJSONObject(0).getString("speaker_id"))
        assertEquals("Он сказал: \"привет\"\nснова", turns.getJSONObject(0).getString("text"))
        assertEquals("speaker_1", turns.getJSONObject(1).getString("speaker_id"))
        assertEquals("speaker_2", turns.getJSONObject(2).getString("speaker_id"))
    }

    @Test fun silenceIsExportedAsAnEmptyTranscript() {
        val json = JSONObject(CompetitionTranscriptJson.serialize(conversation(emptyList())))
        assertEquals(0, json.getJSONArray("transcript").length())
    }

    @Test fun rejectsOutOfBoundsAndZeroLengthTurns() {
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionTranscriptJson.serialize(conversation(listOf(TranscriptSegment("speaker_1", 100, 100, "слово"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionTranscriptJson.serialize(conversation(listOf(TranscriptSegment("speaker_1", 4900, 5100, "слово"))))
        }
    }
}
