package com.pmixay.ascent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class MultilingualRecognizerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun tokensBecomeLowercaseTimedWordsWithoutPunctuation() {
        // Real sherpa-onnx output of Parakeet TDT 0.6B v3 for "Привет, Дима. Как дела?" (1.999 s clip).
        val tokens = arrayOf(" При", "вет", ",", " Д", "и", "ма", ".", " Как", " дела", "?")
        val starts = floatArrayOf(0f, .32f, .48f, .56f, .72f, .88f, 1.12f, 1.2f, 1.52f, 1.84f)
        val durations = floatArrayOf(.32f, .16f, .08f, .16f, .16f, .24f, .08f, .32f, .32f, .08f)
        val words = ParakeetWords.words(tokens, starts, durations, offsetMs = 10_000, limitMs = 60_000)
        assertEquals(listOf("привет", "дима", "как", "дела"), words.map { it.text })
        // Punctuation does not extend a word: "привет" ends with "вет", not with ",".
        assertEquals(TimedWord("привет", 10_000, 10_480), words[0])
        assertEquals(TimedWord("дима", 10_560, 11_120), words[1])
        assertEquals(TimedWord("дела", 11_520, 11_840), words[3])
    }

    @Test fun withoutDurationsAWordEndsAtTheNextTokenAndTheLastOneAfterOneFrame() {
        val words = ParakeetWords.words(arrayOf("▁Hello", "▁world", "!"), floatArrayOf(.1f, .5f, .9f), FloatArray(0), 0, 10_000)
        assertEquals(listOf(TimedWord("hello", 100, 500), TimedWord("world", 500, 900)), words)
        val single = ParakeetWords.words(arrayOf(" ok"), floatArrayOf(2f), FloatArray(0), 0, 10_000)
        assertEquals(listOf(TimedWord("ok", 2_000, 2_080)), single)
    }

    @Test fun innerApostrophesAndHyphensStayAndStrayMarksVanish() {
        assertEquals("don't", ParakeetWords.normalize(" Don’t"))
        assertEquals("кто-то", ParakeetWords.normalize("Кто-то,"))
        assertEquals("", ParakeetWords.normalize(" —"))
        assertEquals("2026", ParakeetWords.normalize(" 2026."))
        val words = ParakeetWords.words(arrayOf(" a", " -", " b"), floatArrayOf(0f, .2f, .4f), floatArrayOf(.1f, .1f, .1f), 0, 1_000)
        assertEquals(listOf("a", "b"), words.map { it.text })
    }

    @Test fun wordsAfterTheRecordingEndAreDroppedAndEndsAreClamped() {
        val words = ParakeetWords.words(arrayOf(" last", " ghost"), floatArrayOf(.9f, 1.2f), floatArrayOf(.3f, .1f), 0, 1_000)
        assertEquals(listOf(TimedWord("last", 900, 1_000)), words)
        assertTrue(ParakeetWords.words(emptyArray(), FloatArray(0), FloatArray(0), 0, 1_000).isEmpty())
    }

    @Test fun installedRequiresEveryPinnedFileWithItsSize() {
        val directory = folder.newFolder(ParakeetFiles.DIRECTORY)
        assertFalse(ParakeetFiles.installed(null))
        assertFalse(ParakeetFiles.installed(directory))
        ParakeetFiles.ALL.forEach { asset ->
            RandomAccessFile(java.io.File(directory, asset.name), "rw").use { it.setLength(asset.bytes) }
        }
        assertTrue(ParakeetFiles.installed(directory))
        RandomAccessFile(java.io.File(directory, ParakeetFiles.TOKENS.name), "rw").use { it.setLength(ParakeetFiles.TOKENS.bytes - 1) }
        assertFalse(ParakeetFiles.installed(directory))
        assertEquals(670_576_784L, ParakeetFiles.ALL.sumOf { it.bytes })
    }

    @Test fun russianAndCisIsTheDefaultMode() {
        assertEquals(RecognitionMode.RUSSIAN_CIS, RecognitionMode.entries.first())
    }
}
