package com.pmixay.ascent

import org.json.JSONArray
import org.json.JSONObject

/** The jury's file format is separate from the encrypted history format. */
internal object CompetitionTranscriptJson {
    fun serialize(conversation: SavedConversation): String {
        val turns = SpeakerAttribution.completeStored(conversation.segments)
            .withIndex().sortedWith(compareBy<IndexedValue<TranscriptSegment>> { it.value.startMs }.thenBy { it.index })
        val transcript = JSONArray()
        turns.forEach { (_, turn) ->
            require(turn.speakerId.isNotBlank() && turn.speakerId != UNKNOWN_SPEAKER) { "Не указан участник" }
            require(turn.startMs >= 0 && turn.startMs < turn.endMs && turn.endMs <= conversation.durationMs) {
                "Некорректное время фразы"
            }
            require(turn.text.isNotBlank()) { "Пустая фраза" }
            transcript.put(JSONObject()
                .put("speaker_id", turn.speakerId)
                .put("start_ms", turn.startMs)
                .put("end_ms", turn.endMs)
                .put("text", turn.text))
        }
        return JSONObject().put("transcript", transcript).toString(2)
    }
}
