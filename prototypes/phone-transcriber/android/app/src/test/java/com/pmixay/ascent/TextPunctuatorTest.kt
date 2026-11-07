package com.pmixay.ascent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPunctuatorTest {
    private val tokenizer = QwenTokenizer.parse(
        requireNotNull(javaClass.getResource("/text-model/qwen-tokenizer-subset.json")).readText(),
    )

    /** Scores every candidate 1 unless [rule] prefers it; records what the stage asked for. */
    private class FakeModel(val rule: (tokens: IntArray, position: Int, candidate: Int) -> Float?) : TextModelScores {
        val calls = mutableListOf<Pair<IntArray, Map<Int, IntArray>>>()
        override fun nextTokenScores(tokens: IntArray, wanted: Map<Int, IntArray>): Map<Int, FloatArray> {
            calls += tokens to wanted
            return wanted.mapValues { (position, ids) -> FloatArray(ids.size) { rule(tokens, position, ids[it]) ?: 1f } }
        }
        override fun close() = Unit
    }

    private val words = "привет маша привет дима как дела".split(' ')

    @Test fun marksAndCapitalsComeFromBoundaryScoresAndWordsNeverChange() {
        // Token IDs from the pinned tokenizer: " маша" = 130838 1478, " привет" ends with 8178,
        // " Д" = 38803, "!" = 0, "?" = 30. Only the boundary after the second "привет" (next
        // word "дима") offers " Д"; the first "привет" is followed by "маша".
        val model = FakeModel { tokens, position, candidate ->
            when {
                candidate == 0 && tokens[position] == 1478 && tokens[position - 1] == 130838 -> 5f
                candidate == 38803 && tokens[position] == 8178 -> 3f
                candidate == 30 && tokens.size == position + 1 -> 5f
                candidate in TextPunctuator.MARKS.map { tokenizer.encode(it.toString()).single() } -> 0f
                else -> null
            }
        }
        val styles = TextPunctuator(tokenizer, model).styles(words)
        val text = TextPunctuator.render(words, styles)
        // "Привет" after "!" is capitalized by the sentence rule although the model preferred lowercase.
        assertEquals("привет маша! Привет Дима как дела?", text)
        assertTrue(TextPunctuator.preservesWords(words.joinToString(" "), text))
        assertEquals(1, model.calls.size)
    }

    @Test fun asksForTheAnswerWordsAfterTheUserTurnWithoutAnyMarks() {
        val model = FakeModel { _, _, _ -> null }
        val styles = TextPunctuator(tokenizer, model).styles(words)
        assertEquals(List(words.size) { WordStyle(capital = false, mark = null) }, styles)
        val (tokens, wanted) = model.calls.single()
        val answer = tokenizer.encode(words.joinToString(" "))
        val user = answer + tokenizer.specialId("<|im_end|>") + tokenizer.encode("\n") +
            tokenizer.specialId("<|im_start|>") + tokenizer.encode("assistant\n")
        assertEquals((user + answer).toList(), tokens.toList())
        // One query per word start, one per boundary; the last boundary weighs the end token.
        assertEquals(words.size + 1, wanted.size)
        assertEquals(listOf(tokenizer.specialId("<|im_end|>"), 11, 13, 30, 0), wanted.getValue(tokens.size - 1).toList())
        assertTrue(wanted.keys.all { it in tokens.indices })
    }

    @Test fun longInputIsSplitIntoBlocksAndASentenceEndCarriesTheCapitalAcross() {
        val long = List(TextPunctuator.MAX_WORDS) { "как" } + listOf("дела")
        val model = FakeModel { tokens, position, candidate ->
            if (candidate == 13 && tokens.size == position + 1) 5f else if (candidate in setOf(11, 13, 30, 0)) 0f else null
        }
        val styles = TextPunctuator(tokenizer, model).styles(long)
        assertEquals(2, model.calls.size)
        assertEquals('.', styles[TextPunctuator.MAX_WORDS - 1].mark)
        assertTrue(styles[TextPunctuator.MAX_WORDS].capital)
        assertEquals('.', styles.last().mark)
    }

    @Test fun validatorRejectsAnyLexicalChange() {
        val raw = "ну ёлка не знаю"
        assertTrue(TextPunctuator.preservesWords(raw, "Ну, ёлка не знаю."))
        assertTrue(TextPunctuator.preservesWords(raw, "Ну елка не знаю?"))
        assertFalse(TextPunctuator.preservesWords(raw, "Ну, ёлка знаю."))
        assertFalse(TextPunctuator.preservesWords(raw, "Ну, ёлка не знаю, ."))
        assertFalse(TextPunctuator.preservesWords(raw, "ёлка ну не знаю"))
        assertFalse(TextPunctuator.preservesWords(raw, "Ну, ёлка, не, знаю, ну"))
    }

    @Test fun rejectsWordsWithWhitespaceOrEmptyText() {
        val stage = TextPunctuator(tokenizer, FakeModel { _, _, _ -> null })
        assertThrows(IllegalArgumentException::class.java) { stage.styles(listOf("как дела")) }
        assertThrows(IllegalArgumentException::class.java) { stage.styles(listOf("")) }
    }
}
