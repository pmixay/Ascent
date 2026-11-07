package com.pmixay.ascent

import java.io.Closeable

/** Capital first letter and optional mark chosen for one recognized word. */
data class WordStyle(val capital: Boolean, val mark: Char?)

/**
 * The local text model as the punctuation stage sees it: one causal pass over [tokens] that
 * continues the model's cached instruction prefix. For each requested position `p` (an index
 * into [tokens]) it returns the scores of the requested candidate IDs for the token that
 * would follow `tokens[p]`, in the order given.
 */
interface TextModelScores : Closeable {
    fun nextTokenScores(tokens: IntArray, wanted: Map<Int, IntArray>): Map<Int, FloatArray>
}

/**
 * Word-preserving punctuation and capitalization with the bundled local text model.
 *
 * The model reads the recognized words once, exactly as recognized (lowercase, no marks), as
 * the answer to an instruction to punctuate them. At each word boundary its next-token scores
 * decide between a mark and the next word, and at each word start between a capital and a
 * lowercase first token. It never generates free text, so it cannot add, drop, reorder or
 * respell a word; speakers and times are untouched. A capital always follows `.`, `?` or `!`.
 */
class TextPunctuator(private val tokenizer: QwenTokenizer, private val model: TextModelScores) {
    private val imStart = tokenizer.specialId("<|im_start|>")
    private val imEnd = tokenizer.specialId("<|im_end|>")
    private val markIds = MARKS.map { mark -> tokenizer.encode(mark.toString()).single() }.toIntArray()

    /** Styles for [words]; long inputs are split into blocks of [MAX_WORDS] words. */
    fun styles(words: List<String>): List<WordStyle> {
        require(words.all { it.isNotEmpty() && it.none(Char::isWhitespace) }) { "Некорректное слово" }
        val result = ArrayList<WordStyle>(words.size)
        for (part in words.chunked(MAX_WORDS)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Обработка отменена")
            val styled = styleBlock(part)
            val afterSentence = result.lastOrNull()?.mark in TERMINAL
            result += if (afterSentence) listOf(styled[0].copy(capital = true)) + styled.drop(1) else styled
        }
        return result
    }

    private fun styleBlock(words: List<String>): List<WordStyle> {
        val user = tokenizer.encode(words.joinToString(" ")) + imEnd + tokenizer.encode("\n") +
            imStart + tokenizer.encode("assistant\n")
        val answer = ArrayList<Int>()
        val starts = IntArray(words.size)
        val lower = IntArray(words.size)
        val upper = IntArray(words.size)
        for ((index, word) in words.withIndex()) {
            val lead = if (index == 0) "" else " "
            val tokens = tokenizer.encode(lead + word)
            starts[index] = answer.size
            answer += tokens.toList()
            lower[index] = tokens[0]
            upper[index] = tokenizer.encode(lead + capitalized(word))[0]
        }
        val tokens = user + answer.toIntArray()
        // Scores at tokens[base + j] are for answer token j; base + answer.size is the end.
        val base = user.size - 1
        // Word i starts at base + starts[i]; the boundary after word i is the next word's start
        // (lowercase, capital, then the marks) or, after the last word, the end of the answer.
        val wanted = HashMap<Int, IntArray>()
        wanted[base] = intArrayOf(lower[0], upper[0])
        for (index in words.indices) {
            val last = index + 1 == words.size
            val boundary = base + if (last) answer.size else starts[index + 1]
            wanted[boundary] = (if (last) intArrayOf(imEnd) else intArrayOf(lower[index + 1], upper[index + 1])) + markIds
        }
        val scores = model.nextTokenScores(tokens, wanted)
        return words.indices.map { index ->
            val start = scores.getValue(base + starts[index])
            val boundary = scores.getValue(base + if (index + 1 < words.size) starts[index + 1] else answer.size)
            val continuation = if (index + 1 < words.size) maxOf(boundary[0], boundary[1]) else boundary[0]
            var best = -1
            for (mark in MARKS.indices) {
                val score = boundary[boundary.size - MARKS.size + mark]
                if (score > continuation && (best < 0 || score > boundary[boundary.size - MARKS.size + best])) best = mark
            }
            WordStyle(capital = start[1] > start[0], mark = if (best >= 0) MARKS[best] else null)
        }.let { styled ->
            styled.mapIndexed { index, style ->
                if (index > 0 && styled[index - 1].mark in TERMINAL) style.copy(capital = true) else style
            }
        }
    }

    companion object {
        /** Words per model pass: every phrase in the 120-clip study fits in one pass. */
        const val MAX_WORDS = 64
        val MARKS = charArrayOf(',', '.', '?', '!')
        private val TERMINAL = setOf('.', '?', '!')
        const val INSTRUCTION = "Расставь знаки препинания и заглавные буквы в тексте распознанной русской речи. " +
            "Нельзя добавлять, удалять, переставлять или заменять слова. " +
            "Верни только исправленный текст без пояснений."

        /** System instruction and the opening of the user turn; the model caches it once. */
        fun instructionPrefix(tokenizer: QwenTokenizer): IntArray =
            intArrayOf(tokenizer.specialId("<|im_start|>")) + tokenizer.encode("system\n$INSTRUCTION") +
                tokenizer.specialId("<|im_end|>") + tokenizer.encode("\n") +
                tokenizer.specialId("<|im_start|>") + tokenizer.encode("user\n")

        fun capitalized(word: String): String = word.replaceFirstChar { it.titlecase() }

        /** Applies [styles] to the original words; word identity and order cannot change. */
        fun render(words: List<String>, styles: List<WordStyle>): String {
            require(words.size == styles.size)
            return words.indices.joinToString(" ") { index ->
                val word = if (styles[index].capital) capitalized(words[index]) else words[index]
                styles[index].mark?.let { word + it } ?: word
            }
        }

        /**
         * Independent check that [presented] carries exactly [raw]'s words: lowercase, `ё` as `е`
         * and the four marks removed. The stage keeps the raw text whenever this fails.
         */
        fun preservesWords(raw: String, presented: String): Boolean {
            fun words(text: String, marks: Boolean) = text.split(' ').filter { it.isNotEmpty() }
                .map { (if (marks) it.dropLastWhile { c -> c in MARKS } else it).lowercase().replace('ё', 'е') }
            val shown = words(presented, marks = true)
            return shown.none { it.isEmpty() } && words(raw, marks = false) == shown
        }
    }
}
