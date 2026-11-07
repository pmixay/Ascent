package com.pmixay.ascent

import org.json.JSONArray
import org.json.JSONObject

/**
 * The jury's file format and nothing else: `{"transcript":[{"speaker_id","start_ms","end_ms","text"}]}`.
 *
 * This is deliberately separate from the encrypted history format in [ConversationStore], so
 * renaming a history field can never change an export. Rules, from the plan's export section:
 * - times are integer milliseconds in original-media coordinates, `0 <= start_ms < end_ms <= duration`;
 *   out-of-range values from the approximate CTC timing are clamped, never dropped;
 * - entries are ordered by start, then end, keeping the input order for ties; overlap is preserved;
 * - `speaker_id` is the stable anonymous ID, never the editable display name, and is never blank;
 * - `text` is the recognizer's words ([TranscriptSegment.recognizedText]), not the presentation edit;
 * - the inferred-attribution flag stays internal because the brief defines no such key;
 * - silence produces `{"transcript":[]}`.
 */
object CompetitionJsonExporter {
    private data class Entry(val speakerId: String, val startMs: Long, val endMs: Long, val text: String)

    fun toJson(conversation: SavedConversation): String = toJson(conversation.segments, conversation.durationMs)

    fun toJson(segments: List<TranscriptSegment>, mediaDurationMs: Long): String {
        require(mediaDurationMs >= 0) { "Некорректная длительность записи" }
        val spoken = segments.filter { it.recognizedText.isNotBlank() }
        require(spoken.isEmpty() || mediaDurationMs > 0) { "Не удалось определить длительность записи" }
        val entries = SpeakerAttribution.completeStored(spoken).map { segment ->
            val start = segment.startMs.coerceIn(0L, mediaDurationMs - 1)
            val end = segment.endMs.coerceIn(start + 1, mediaDurationMs)
            Entry(segment.speakerId, start, end, segment.recognizedText.trim())
        }.sortedWith(compareBy<Entry>({ it.startMs }, { it.endMs }))
        val transcript = JSONArray()
        for (entry in entries) {
            transcript.put(JSONObject()
                .put("speaker_id", entry.speakerId)
                .put("start_ms", entry.startMs)
                .put("end_ms", entry.endMs)
                .put("text", entry.text))
        }
        return JSONObject().put("transcript", transcript).toString()
    }
}
