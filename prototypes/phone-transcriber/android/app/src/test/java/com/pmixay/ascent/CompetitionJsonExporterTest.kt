package com.pmixay.ascent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import javax.crypto.KeyGenerator

class CompetitionJsonExporterTest {
    private fun segment(speaker: String, start: Long, end: Long, text: String, raw: String? = null, inferred: Boolean = false) =
        TranscriptSegment(speaker, start, end, text, inferred, raw)

    private fun parse(json: String): List<JSONObject> {
        val root = JSONObject(json)
        assertEquals(setOf("transcript"), root.keys().asSequence().toSet())
        val items = root.getJSONArray("transcript")
        return List(items.length()) { items.getJSONObject(it) }
    }

    @Test fun silenceExportsAnEmptyTranscriptArray() {
        assertEquals("""{"transcript":[]}""", CompetitionJsonExporter.toJson(emptyList(), 0))
        assertEquals("""{"transcript":[]}""", CompetitionJsonExporter.toJson(emptyList(), 61_000))
    }

    @Test fun everyEntryHasExactlyTheBriefsKeysAndTypes() {
        val json = CompetitionJsonExporter.toJson(
            listOf(segment("speaker_1", 120, 2_400, "Здравствуйте"), segment("speaker_2", 2_400, 3_100, "Добрый день", inferred = true)), 4_000)
        val entries = parse(json)
        assertEquals(2, entries.size)
        for (entry in entries) {
            assertEquals(setOf("speaker_id", "start_ms", "end_ms", "text"), entry.keys().asSequence().toSet())
            assertTrue(entry.get("speaker_id") is String)
            assertTrue(entry.get("text") is String)
            // Integer milliseconds: org.json parses 120 as Integer or Long, never as a decimal.
            assertTrue(entry.get("start_ms") is Int || entry.get("start_ms") is Long)
            assertTrue(entry.get("end_ms") is Int || entry.get("end_ms") is Long)
        }
        assertFalse("decimal point in a time field", Regex("\"(start|end)_ms\":\\d+\\.").containsMatchIn(json))
        assertFalse("inferred attribution must stay internal", json.contains("inferred"))
        assertEquals("speaker_2", entries[1].getString("speaker_id"))
        assertEquals(2_400L, entries[1].getLong("start_ms"))
    }

    @Test fun exportsTheRecognizersWordsNotThePresentationEdit() {
        val entries = parse(CompetitionJsonExporter.toJson(
            listOf(segment("speaker_1", 0, 1_000, "Привет, мир.", raw = "привет мир")), 1_000))
        assertEquals("привет мир", entries.single().getString("text"))
    }

    @Test fun ordersByStartThenEndKeepingInputOrderForTiesAndPreservesOverlap() {
        val entries = parse(CompetitionJsonExporter.toJson(listOf(
            segment("speaker_2", 5_000, 6_000, "четвёртый"),
            segment("speaker_1", 1_000, 4_000, "первый"),
            segment("speaker_2", 2_000, 3_000, "второй"),
            segment("speaker_1", 2_000, 3_000, "третий"),
        ), 10_000))
        assertEquals(listOf("первый", "второй", "третий", "четвёртый"), entries.map { it.getString("text") })
        // The second and third entries overlap the first; overlap is output, not removed.
        assertTrue(entries[1].getLong("start_ms") < entries[0].getLong("end_ms"))
    }

    @Test fun clampsApproximateTimesIntoTheMediaDurationAndKeepsStartBeforeEnd() {
        val entries = parse(CompetitionJsonExporter.toJson(listOf(
            segment("speaker_1", -50, 500, "начало"),
            segment("speaker_1", 900, 900, "точка"),
            segment("speaker_1", 990, 1_400, "хвост"),
            segment("speaker_1", 1_200, 1_300, "за пределом"),
        ), 1_000))
        assertEquals(4, entries.size)
        for (entry in entries) {
            val start = entry.getLong("start_ms")
            val end = entry.getLong("end_ms")
            assertTrue("0 <= $start < $end <= 1000", start in 0 until end && end <= 1_000)
        }
        assertEquals(0L, entries[0].getLong("start_ms"))
    }

    @Test fun escapesQuotesBackslashesNewlinesAndKeepsCyrillicAndEmojiAsRealCharacters() {
        val text = "Он сказал: «да» \"нет\" \\ путь\nвторая строка\tтаб ёжик 😀"
        val json = CompetitionJsonExporter.toJson(listOf(segment("speaker_1", 0, 1_000, text)), 1_000)
        assertEquals(text, parse(json).single().getString("text"))
        assertFalse("raw newline inside a JSON string", json.contains('\n'))
        assertTrue(json.contains("ёжик"))
    }

    @Test fun speakerIdsAreStableAndIndependentOfDisplayNames() {
        val segments = listOf(segment("speaker_1", 0, 500, "один"), segment("speaker_2", 600, 900, "два"))
        val first = CompetitionJsonExporter.toJson(SavedConversation("a", 1, 1_000, segments, mapOf("speaker_1" to "Анна")))
        val renamed = CompetitionJsonExporter.toJson(SavedConversation("a", 1, 1_000, segments, mapOf("speaker_1" to "Борис", "speaker_2" to "Анна")))
        assertEquals(first, renamed)
        assertFalse(first.contains("Анна"))
    }

    @Test fun everyEntryCarriesAParticipantLabelEvenForLegacyUnknownSpeakers() {
        val entries = parse(CompetitionJsonExporter.toJson(listOf(
            segment("speaker_1", 0, 500, "один"),
            segment(UNKNOWN_SPEAKER, 600, 900, "два"),
            segment("", 1_000, 1_200, "три"),
        ), 2_000))
        assertTrue(entries.all { it.getString("speaker_id").isNotBlank() && it.getString("speaker_id") != UNKNOWN_SPEAKER })
    }

    @Test fun blankTextCarriesNoWordsAndIsNotExported() {
        val entries = parse(CompetitionJsonExporter.toJson(
            listOf(segment("speaker_1", 0, 500, "   "), segment("speaker_1", 600, 900, "слово")), 1_000))
        assertEquals(listOf("слово"), entries.map { it.getString("text") })
    }

    @Test fun spokenWordsWithoutADurationAreRefusedInsteadOfGuessingBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionJsonExporter.toJson(listOf(segment("speaker_1", 0, 500, "слово")), 0)
        }
        assertThrows(IllegalArgumentException::class.java) { CompetitionJsonExporter.toJson(emptyList(), -1) }
    }

    @Test fun encryptedHistoryKeepsRawTextAndSourceNameAndOldRecordsStillLoad() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val now = 1_800_000_000_000L
        val root = Files.createTempDirectory("competition-store").toFile()
        try {
            val store = ConversationStore(root, { key }, { now })
            val imported = SavedConversation("import-1", now - 1_000, 5_000,
                listOf(segment("speaker_1", 0, 900, "Привет.", raw = "привет"), segment("speaker_1", 1_000, 1_900, "дальше")),
                mapOf("speaker_1" to "Участник 1"), sourceName = "звонок.wav")
            val microphone = SavedConversation("mic-1", now - 2_000, 5_000,
                listOf(segment("speaker_1", 0, 900, "слово")), mapOf("speaker_1" to "Участник 1"))
            store.save(listOf(imported, microphone))
            val loaded = store.load().associateBy { it.id }
            assertEquals("звонок.wav", loaded.getValue("import-1").sourceName)
            assertEquals("привет", loaded.getValue("import-1").segments[0].rawText)
            assertNull(loaded.getValue("import-1").segments[1].rawText)
            assertNull(loaded.getValue("mic-1").sourceName)
            // The stored JSON of a record without the new fields is unchanged.
            val plain = ConversationStore.conversationJson(microphone).toString()
            assertFalse(plain.contains("sourceName") || plain.contains("rawText"))
        } finally { root.deleteRecursively() }
    }
}
