package com.pmixay.ascent

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QwenTokenizerTest {
    /**
     * Subset of the pinned `tokenizer.json`: every vocabulary entry and merge that can apply to
     * the sample strings, in the original rank order, so encoding them is unchanged. The expected
     * IDs were produced by the Hugging Face `tokenizers` library from the complete file.
     */
    private val fixture = requireNotNull(javaClass.getResource("/text-model/qwen-tokenizer-subset.json")).readText()
    private val tokenizer = QwenTokenizer.parse(fixture)

    @Test fun matchesTheReferenceTokenizerForRussianEnglishDigitsAndWhitespace() {
        val expected = JSONObject(fixture).getJSONArray("expected")
        assertEquals(19, expected.length())
        for (index in 0 until expected.length()) {
            val sample = expected.getJSONObject(index)
            val ids = sample.getJSONArray("ids")
            assertArrayEquals(sample.getString("text"), IntArray(ids.length()) { ids.getInt(it) }, tokenizer.encode(sample.getString("text")))
        }
    }

    @Test fun exposesChatControlTokensAndSingleTokenMarks() {
        assertEquals(151644, tokenizer.specialId("<|im_start|>"))
        assertEquals(151645, tokenizer.specialId("<|im_end|>"))
        assertArrayEquals(intArrayOf(11), tokenizer.encode(","))
        assertArrayEquals(intArrayOf(13), tokenizer.encode("."))
        assertArrayEquals(intArrayOf(30), tokenizer.encode("?"))
        assertArrayEquals(intArrayOf(0), tokenizer.encode("!"))
        assertThrows(IllegalArgumentException::class.java) { tokenizer.specialId("<|unknown|>") }
    }

    @Test fun instructionPrefixIsTheChatTemplateOpening() {
        val prefix = TextPunctuator.instructionPrefix(tokenizer)
        assertEquals(151644, prefix.first())
        assertArrayEquals(intArrayOf(151644, 872, 198), prefix.copyOfRange(prefix.size - 3, prefix.size))
    }
}
