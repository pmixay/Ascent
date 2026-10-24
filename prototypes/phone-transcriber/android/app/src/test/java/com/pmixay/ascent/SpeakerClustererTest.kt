package com.pmixay.ascent

import org.junit.Assert.*
import org.junit.Test

class SpeakerClustererTest {
    @Test fun returningVoiceKeepsItsId() {
        val model = SpeakerClusterer()
        assertEquals("speaker_1", model.add(floatArrayOf(1f, 0f)))
        assertEquals("speaker_2", model.add(floatArrayOf(0f, 1f)))
        assertEquals("speaker_1", model.add(floatArrayOf(0.98f, 0.02f)))
        assertEquals("speaker_2", model.classify(floatArrayOf(0.03f, 0.97f)))
    }

    @Test fun invalidOrUnrepresentableVoiceIsNotAssignedAnIdentity() {
        val model = SpeakerClusterer(maxSpeakers = 1)
        assertEquals(SpeakerClusterer.UNKNOWN, model.add(floatArrayOf(0f, 0f)))
        assertEquals(SpeakerClusterer.UNKNOWN, model.add(floatArrayOf(Float.NaN, 0f)))
        model.add(floatArrayOf(1f, 0f))
        assertEquals(SpeakerClusterer.UNKNOWN, model.add(floatArrayOf(0f, 1f)))
    }
}
