package com.pmixay.ascent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Byte-level BPE encoder for the bundled Qwen2.5 text model's `tokenizer.json`.
 *
 * It reproduces the file's NFC normalizer, Qwen2 split pattern, byte-to-character map and
 * merge ranks, so the token IDs equal those of the Hugging Face tokenizer for the same file.
 * Only encoding is needed: the punctuation stage never decodes generated text.
 */
class QwenTokenizer private constructor(
    private val vocabulary: Map<String, Int>,
    private val ranks: Map<String, Int>,
    private val special: Map<String, Int>,
) {
    fun encode(text: String): IntArray {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val ids = ArrayList<Int>()
        val matcher = SPLIT.matcher(normalized)
        while (matcher.find()) {
            val piece = buildString { matcher.group().toByteArray(Charsets.UTF_8).forEach { append(BYTE_CHARS[it.toInt() and 255]) } }
            merge(piece, ids)
        }
        return ids.toIntArray()
    }

    fun specialId(content: String): Int = requireNotNull(special[content]) { "Нет служебного токена $content" }

    private fun merge(piece: String, into: MutableList<Int>) {
        val parts = piece.map { it.toString() }.toMutableList()
        while (parts.size > 1) {
            var best = Int.MAX_VALUE
            var at = -1
            for (index in 0 until parts.size - 1) {
                val rank = ranks[parts[index] + " " + parts[index + 1]] ?: continue
                if (rank < best) { best = rank; at = index }
            }
            if (at < 0) break
            parts[at] = parts[at] + parts.removeAt(at + 1)
        }
        parts.forEach { into += requireNotNull(vocabulary[it]) { "Токен вне словаря текстовой модели" } }
    }

    companion object {
        private val SPLIT: Pattern = Pattern.compile(
            "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+",
            Pattern.UNICODE_CHARACTER_CLASS,
        )

        /** GPT-2 byte map: printable bytes keep their code point, the rest move above U+00FF. */
        private val BYTE_CHARS = CharArray(256).also { chars ->
            val printable = (33..126) + (161..172) + (174..255)
            var extra = 0
            for (byte in 0..255) chars[byte] = if (byte in printable) byte.toChar() else (256 + extra++).toChar()
        }

        fun load(file: File): QwenTokenizer = parse(file.readText(Charsets.UTF_8))

        fun parse(json: String): QwenTokenizer {
            val root = JSONObject(json)
            val model = root.getJSONObject("model")
            require(model.getString("type") == "BPE") { "Ожидается BPE-токенизатор" }
            val vocabJson = model.getJSONObject("vocab")
            val vocabulary = HashMap<String, Int>(vocabJson.length() * 2)
            for (key in vocabJson.keys()) vocabulary[key] = vocabJson.getInt(key)
            val merges = model.getJSONArray("merges")
            val ranks = HashMap<String, Int>(merges.length() * 2)
            for (index in 0 until merges.length()) {
                val item = merges.get(index)
                val key = if (item is JSONArray) item.getString(0) + " " + item.getString(1) else item as String
                ranks.putIfAbsent(key, index)
            }
            val added = root.getJSONArray("added_tokens")
            val special = HashMap<String, Int>()
            for (index in 0 until added.length()) {
                val token = added.getJSONObject(index)
                special[token.getString("content")] = token.getInt("id")
            }
            return QwenTokenizer(vocabulary, ranks, special)
        }
    }
}
