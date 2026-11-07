package com.pmixay.ascent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageProbeTest {
    private fun words(vararg text: String) = text.mapIndexed { index, word -> TimedWord(word, index * 500L, index * 500L + 400) }

    @Test fun latinMajorityChoosesTheMultilingualModeOnceEnoughLettersAreSeen() {
        val probe = LanguageProbe()
        assertNull(probe.observe(words("so", "what", "do"), 20_000))  // 8 letters: undecided
        // GigaAM's Cyrillic drift on English still loses to the Latin majority.
        assertEquals(RecognitionMode.MULTILINGUAL, probe.observe(words("we", "need", "to", "talk", "about", "транскрипн", "box"), 40_000))
        assertEquals(RecognitionMode.MULTILINGUAL, probe.decision)
    }

    @Test fun cyrillicMajorityOrATieKeepsTheRussianMode() {
        val probe = LanguageProbe()
        assertEquals(RecognitionMode.RUSSIAN_CIS, probe.observe(words("привет", "маша", "как", "дела", "у", "тебя", "сегодня", "ok"), 20_000))
        val tie = LanguageProbe()
        assertEquals(RecognitionMode.RUSSIAN_CIS, tie.observe(words("abcdefghijklmno", "абвгдежзийклмно"), 20_000))
    }

    @Test fun kazakhLettersCountAsNonLatin() {
        assertEquals(RecognitionMode.RUSSIAN_CIS, LanguageProbe().observe(words("қазақстан", "әлем", "ұлы", "өмір", "ғылым", "достар", "ok"), 20_000))
    }

    @Test fun silenceDecidesRussianAtTheProbeLimitAndTheDecisionIsFinal() {
        val probe = LanguageProbe()
        assertNull(probe.observe(emptyList(), 40_000))
        assertEquals(RecognitionMode.RUSSIAN_CIS, probe.observe(emptyList(), LanguageProbe.MAX_PROBE_MS))
        // Later words never change an earlier decision.
        assertEquals(RecognitionMode.RUSSIAN_CIS, probe.observe(words("hello", "world", "this", "is", "english", "speech"), 80_000))
    }

    @Test fun autoIsAModeAndRussianStaysTheFirst() {
        assertEquals(listOf(RecognitionMode.RUSSIAN_CIS, RecognitionMode.MULTILINGUAL, RecognitionMode.AUTO), RecognitionMode.entries.toList())
    }
}
