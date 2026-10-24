package com.pmixay.ascent

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real ERes2NetV2 vectors from public Russian readers; not a natural-conversation DER benchmark. */
class RealSpeakerRegressionTest {
    private data class Example(val observation: SpeakerObservation, val reader: String)
    private data class Clip(val id: String, val reader: String, val variant: String,
                            val durationMs: Long, val examples: List<Example>)

    @Test fun oneReaderKeepsOneLabelAcrossUtterancesGainAndNoise() {
        val failures = mutableListOf<String>()
        for ((reader, clips) in fixtures.groupBy { it.reader }) {
            val examples = arrange(clips)
            val result = SpeakerClusterer().cluster(examples.map { it.observation })
            val identified = result.assignments.filter { it.speakerId != SpeakerClusterer.UNKNOWN }
            val ids = identified.map { it.speakerId }.toSet()
            val unknownRate = 1.0 - identified.size.toDouble() / examples.size
            val diagnostic = "Russian reader $reader: windows=${examples.size}, speakers=${ids.size}, unknown=$unknownRate"
            println(diagnostic)
            if (ids.size != 1 || unknownRate > 0.15) failures += diagnostic
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun returningReadersStayDistinctAcrossDifferentUtterances() {
        // Alternate real readers, then return with another utterance/channel condition.
        val byReader = fixtures.groupBy { it.reader }.toSortedMap().values.map { clips ->
            clips.sortedWith(compareBy<Clip>({ it.variant }, { it.id }))
        }
        val clips = (0 until byReader.maxOf { it.size }).flatMap { index -> byReader.mapNotNull { it.getOrNull(index) } }
        val examples = arrange(clips)
        val result = SpeakerClusterer().cluster(examples.map { it.observation })
        val predictions = result.assignments.map { it.speakerId }
        val ids = predictions.filter { it != SpeakerClusterer.UNKNOWN }.toSet()
        val readers = examples.map { it.reader }.distinct()
        val matches = readers.map { reader ->
            ids.associateWith { id -> examples.indices.count { examples[it].reader == reader && predictions[it] == id } }
        }
        fun bestMapping(index: Int, used: Set<String>): Int {
            if (index == matches.size) return 0
            return ids.filter { it !in used }.maxOfOrNull { id ->
                matches[index].getValue(id) + bestMapping(index + 1, used + id)
            } ?: 0
        }
        val errorRate = 1.0 - bestMapping(0, emptySet()).toDouble() / examples.size
        val unknownRate = predictions.count { it == SpeakerClusterer.UNKNOWN }.toDouble() / examples.size
        val diagnostic = "Russian alternating readers: windows=${examples.size}, speakers=${ids.size}, " +
            "assignmentError=$errorRate, unknown=$unknownRate"
        println(diagnostic)
        assertEquals(diagnostic, 3, ids.size)
        assertTrue(diagnostic, errorRate <= 0.15)
        assertTrue(diagnostic, unknownRate <= 0.15)
    }

    private fun arrange(clips: List<Clip>): List<Example> {
        var offset = 0L
        return clips.flatMap { clip ->
            val shifted = clip.examples.map { item ->
                val source = item.observation
                Example(source.copy(startMs = source.startMs + offset, endMs = source.endMs + offset,
                    speech = source.speech.map { SpeechSpan(it.startMs + offset, it.endMs + offset) }), item.reader)
            }
            offset += clip.durationMs + 1200
            shifted
        }
    }

    private val fixtures: List<Clip> by lazy {
        val resource = requireNotNull(javaClass.getResourceAsStream("/speaker-embeddings.json")) {
            "CI must generate the real speaker regression fixtures before JVM tests"
        }
        val json = resource.bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(1, json.getInt("schema_version"))
        assertEquals("bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239", json.getString("model_sha256"))
        assertEquals(16000, json.getInt("sample_rate"))
        val source = json.getJSONArray("fixtures")
        assertEquals(6, source.length())
        buildList {
            for (i in 0 until source.length()) {
                val fixture = source.getJSONObject(i)
                val variants = fixture.getJSONArray("variants")
                for (j in 0 until variants.length()) {
                    val variant = variants.getJSONObject(j)
                    val file = File.createTempFile("ascent-public-speaker-", ".wav")
                    try {
                        requireNotNull(javaClass.getResourceAsStream("/" + variant.getString("audio_file"))).use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                        val examples = PcmWave(file).use { wave ->
                            val windows = variant.getJSONArray("windows")
                            buildList {
                                for (k in 0 until windows.length()) {
                                    val window = windows.getJSONObject(k)
                                    val start = window.getLong("start_ms")
                                    val end = window.getLong("end_ms")
                                    val samples = wave.read(start * 16, ((end - start) * 16).toInt())
                                    val activity = SpeakerSpeechSupport.activity(samples, start)
                                    samples.fill(0f)
                                    val speechMs = activity.spans.sumOf { it.endMs - it.startMs }
                                    if (speechMs < 320 || activity.quality < .20f) continue
                                    val values = window.getJSONArray("embedding")
                                    assertEquals(192, values.length())
                                    val vector = FloatArray(values.length()) { values.getDouble(it).toFloat() }
                                    add(Example(SpeakerObservation(start, end, vector, activity.quality,
                                        speechMs >= 800, activity.spans), fixture.getString("speaker")))
                                }
                            }
                        }
                        assertTrue("No usable public speech: ${fixture.getString("id")}", examples.size >= 2)
                        val stable = SpeakerEvidence.stableSeeds(examples.map { it.observation }, List(examples.size) { 0 })
                        add(Clip(fixture.getString("id"), fixture.getString("speaker"), variant.getString("name"),
                            fixture.getLong("duration_ms"), examples.mapIndexed { index, item -> item.copy(observation = stable[index]) }))
                    } finally { file.delete() }
                }
            }
        }.also { clips ->
            assertEquals(3, clips.map { it.reader }.distinct().size)
            assertEquals(setOf("clean", "gain035", "noise20db"), clips.map { it.variant }.toSet())
            val clean = clips.filter { it.variant == "clean" }
            val within = mutableListOf<Float>()
            val between = mutableListOf<Float>()
            for (a in clean.indices) for (b in a + 1 until clean.size) {
                for (left in clean[a].examples) for (right in clean[b].examples) {
                    if (!left.observation.canSeed || !right.observation.canSeed) continue
                    val score = SpeakerClusterer.dot(SpeakerClusterer.normalized(left.observation.vector)!!,
                        SpeakerClusterer.normalized(right.observation.vector)!!)
                    (if (clean[a].reader == clean[b].reader) within else between).add(score)
                }
            }
            fun quantiles(values: List<Float>): List<Float> = values.sorted().let { sorted ->
                listOf(.1, .5, .9).map { sorted[((sorted.size - 1) * it).toInt()] }
            }
            println("Russian clean different-utterance cosine p10/p50/p90: same=${quantiles(within)}, different=${quantiles(between)}")
        }
    }
}
