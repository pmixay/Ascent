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

    @Test fun sparseShortContextsDoNotIncreaseFragmentationAgainstFrozenBaseline() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/speaker-embeddings.json"))
        val document = resource.bufferedReader().use { JSONObject(it.readText()) }
        val fragmentation = document.getJSONObject("fragmentation")
        assertEquals("f263faddccb6228d85dff5e798aac94dd5fd077bcbf0f01706cea3878ccbb345",
            fragmentation.getString("source_manifest_sha256"))
        val cases = fragmentation.getJSONArray("cases")
        assertEquals(7, cases.length())
        val expectedReaderMs = mapOf("reader_2671" to 34690L, "reader_2826" to 14680L,
            "reader_3056" to 25860L, "reader_4372" to 12480L, "reader_4471" to 20700L,
            "reader_5548" to 13500L)
        val failures = mutableListOf<String>()
        var baselineCorrectMs = 0L
        var candidateCorrectMs = 0L
        var totalMs = 0L
        for (caseIndex in 0 until cases.length()) {
            val case = cases.getJSONObject(caseIndex)
            val name = case.getString("name")
            val emissions = case.getJSONArray("emissions")
            val words = (0 until emissions.length()).map { index -> emissions.getJSONObject(index).let {
                TimedWord("emission", it.getLong("start_ms"), it.getLong("end_ms"))
            } }
            val references = case.getJSONArray("references")
            val probes = (0 until references.length()).map { index -> references.getJSONObject(index).let {
                TimedWord("reference", it.getLong("start_ms"), it.getLong("end_ms"))
            } }
            val readers = (0 until references.length()).map { references.getJSONObject(it).getString("speaker") }
            val actualReaderMs = probes.indices.groupBy { readers[it] }.mapValues { (_, indices) ->
                indices.sumOf { probes[it].endMs - probes[it].startMs }
            }
            assertEquals("Sparse-context reference coverage changed",
                if (name == "alternating") expectedReaderMs else mapOf(name to expectedReaderMs.getValue(name)),
                actualReaderMs)
            val referenceSources = (0 until references.length()).filter {
                references.getJSONObject(it).getString("turn_kind") == "reference"
            }.map { references.getJSONObject(it).getString("source_id") }.toSet()
            val querySources = (0 until references.length()).filter {
                references.getJSONObject(it).getString("turn_kind") != "reference"
            }.map { references.getJSONObject(it).getString("source_id") }.toSet()
            assertTrue("Reference/query utterances must be disjoint", referenceSources.intersect(querySources).isEmpty())
            val contexts = SpeakerSpeechSupport.contexts(words, case.getLong("duration_ms"))
            val windows = case.getJSONArray("windows")
            assertEquals("Sparse-emission context mirror diverged", contexts.size, windows.length())
            assertTrue("Must exercise short app contexts", contexts.any { it.endMs - it.startMs == 1200L })
            val observations = mutableListOf<SpeakerObservation>()
            val regions = mutableListOf<Int>()
            val file = File.createTempFile("ascent-public-fragmentation-", ".wav")
            try {
                requireNotNull(javaClass.getResourceAsStream("/" + case.getString("audio_file"))).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                PcmWave(file).use { wave ->
                    assertEquals(case.getLong("duration_ms"), wave.durationMs)
                    for ((index, context) in contexts.withIndex()) {
                        val window = windows.getJSONObject(index)
                        assertEquals(context.startMs, window.getLong("start_ms"))
                        assertEquals(context.endMs, window.getLong("end_ms"))
                        assertEquals(context.region, window.getInt("region"))
                        val samples = wave.read(context.startMs * 16, ((context.endMs - context.startMs) * 16).toInt())
                        val activity = SpeakerSpeechSupport.activity(samples, context.startMs)
                        samples.fill(0f)
                        val speechMs = activity.spans.sumOf { it.endMs - it.startMs }
                        if (speechMs < 320 || activity.quality < .20f) continue
                        val values = window.getJSONArray("embedding")
                        assertEquals(192, values.length())
                        observations += SpeakerObservation(context.startMs, context.endMs,
                            FloatArray(values.length()) { values.getDouble(it).toFloat() },
                            activity.quality, speechMs >= 800, activity.spans)
                        regions += context.region
                    }
                }
                val stable = SpeakerEvidence.stableSeeds(observations, regions)
                val baseline = FragmentationBaselineClusterer().cluster(stable)
                val candidate = SpeakerClusterer().cluster(stable)
                fun score(result: SpeakerClustering): Pair<SpeakerAttributionScore, Int> {
                    val predictions = SpeakerSpeechSupport.speakersForWords(probes, stable, result.assignments)
                    val cells = probes.indices.map { index -> AttributionCell(readers[index], probes[index].startMs,
                        probes[index].endMs, predictions[index]) }
                    return SpeakerAttributionScorer.score(cells) to
                        predictions.filter { it != SpeakerClusterer.UNKNOWN }.distinct().size
                }
                val (oldScore, oldLabels) = score(baseline)
                val (newScore, newLabels) = score(candidate)
                assertEquals(oldScore.totalMs, newScore.totalMs)
                val diagnostic = "Russian sparse-context DEVELOPMENT $name: totalMs=${newScore.totalMs}, " +
                    "referenceReaders=${readers.distinct().size}, contexts=${contexts.size}, " +
                    "acceptedContexts=${observations.size}, baselineLabels=$oldLabels, candidateLabels=$newLabels, " +
                    "baselineCorrectMs=${oldScore.correctMs}, candidateCorrectMs=${newScore.correctMs}, " +
                    "baselineAccuracy=${oldScore.accuracy}, candidateAccuracy=${newScore.accuracy}, " +
                    "baselineUnknown=${oldScore.unknownRate}, candidateUnknown=${newScore.unknownRate}"
                println(diagnostic)
                println("Russian sparse-context DEVELOPMENT $name per reader: " +
                    "baseline=${oldScore.perReaderAccuracy}, candidate=${newScore.perReaderAccuracy}")
                if (newLabels > oldLabels || newScore.correctMs < oldScore.correctMs ||
                    oldScore.perReaderAccuracy.any { (reader, accuracy) ->
                        newScore.perReaderAccuracy.getValue(reader) < accuracy
                    }) failures += diagnostic
                baselineCorrectMs += oldScore.correctMs
                candidateCorrectMs += newScore.correctMs
                totalMs += newScore.totalMs
            } finally {
                observations.forEach { it.vector.fill(0f) }
                file.delete()
            }
        }
        println("Russian sparse-context DEVELOPMENT paired: baselineCorrectMs=$baselineCorrectMs, " +
            "candidateCorrectMs=$candidateCorrectMs, totalMs=$totalMs; " +
            "correlated public excerpts with synthetic pauses, not phone or natural-conversation accuracy")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

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
            println("Russian reader $reader: signatureEvidence=" + result.signatures.map {
                "speechMs=${it.speechMs}, windows=${it.windowCount}, cohesion=${it.cohesion}"
            })
            if (ids.size != 1 || unknownRate > 0.15) {
                var offset = 0
                for (clip in clips) {
                    val assignments = result.assignments.subList(offset, offset + clip.examples.size)
                    println("Russian split detail ${clip.id}/${clip.variant}: " +
                        assignments.groupingBy { it.speakerId }.eachCount())
                    offset += clip.examples.size
                }
                for (left in result.signatures.indices) for (right in left + 1 until result.signatures.size) {
                    val a = result.signatures[left]
                    val b = result.signatures[right]
                    println("Russian split centroid ${a.speakerId}/${b.speakerId}: " +
                        SpeakerClusterer.dot(a.vector, b.vector))
                }
                developmentCentroidDiagnostics(examples, reader)
                failures += diagnostic
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun continuousReaderDisjointClipTimeAccuracyExceedsSeventyFivePercent() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/speaker-embeddings.json"))
        val document = resource.bufferedReader().use { JSONObject(it.readText()) }
        val evaluation = document.getJSONObject("evaluation")
        assertEquals("fb7dc2c6875f36e9dd751c8d9b260ea6e620b302d99c8cc4f9d2c32e0daa699f",
            evaluation.getString("source_manifest_sha256"))
        // Frozen independently of the activity gate and clustering output. Every source
        // sample is scored once per condition, including internal pauses and unknowns.
        val expectedReaderMs = mapOf("reader_13587" to 24100L, "reader_295" to 21060L,
            "reader_5397" to 9880L, "reader_8086" to 17870L, "reader_8169" to 15340L,
            "reader_9014" to 10420L)
        val cases = evaluation.getJSONArray("cases")
        assertEquals(4, cases.length())
        val conditions = mutableSetOf<String>()
        val failures = mutableListOf<String>()
        var totalMs = 0L
        var correctMs = 0L
        var unknownMs = 0L
        var macroAccuracySum = 0.0
        for (caseIndex in 0 until cases.length()) {
            val case = cases.getJSONObject(caseIndex)
            val condition = case.getString("condition")
            assertTrue(conditions.add(condition))
            val source = case.getJSONArray("probes")
            val words = (0 until source.length()).map { index ->
                val probe = source.getJSONObject(index)
                TimedWord("probe", probe.getLong("start_ms"), probe.getLong("end_ms"))
            }
            val readers = (0 until source.length()).map { source.getJSONObject(it).getString("speaker") }
            val turnKinds = (0 until source.length()).map { source.getJSONObject(it).getString("turn_kind") }
            val actualReaderMs = words.indices.groupBy { readers[it] }.mapValues { (_, indices) ->
                indices.sumOf { words[it].endMs - words[it].startMs }
            }
            assertEquals("Reference coverage changed", expectedReaderMs, actualReaderMs)
            val contexts = SpeakerSpeechSupport.contexts(words, case.getLong("duration_ms"))
            val windows = case.getJSONArray("windows")
            assertEquals("Python context mirror diverged from app", contexts.size, windows.length())
            val observations = mutableListOf<SpeakerObservation>()
            val regions = mutableListOf<Int>()
            val file = File.createTempFile("ascent-public-conversation-", ".wav")
            try {
                requireNotNull(javaClass.getResourceAsStream("/" + case.getString("audio_file"))).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                PcmWave(file).use { wave ->
                    assertEquals(case.getLong("duration_ms"), wave.durationMs)
                    for ((index, context) in contexts.withIndex()) {
                        val window = windows.getJSONObject(index)
                        assertEquals(context.startMs, window.getLong("start_ms"))
                        assertEquals(context.endMs, window.getLong("end_ms"))
                        assertEquals(context.region, window.getInt("region"))
                        val samples = wave.read(context.startMs * 16, ((context.endMs - context.startMs) * 16).toInt())
                        val activity = SpeakerSpeechSupport.activity(samples, context.startMs)
                        samples.fill(0f)
                        val speechMs = activity.spans.sumOf { it.endMs - it.startMs }
                        if (speechMs < 320 || activity.quality < .20f) continue
                        val values = window.getJSONArray("embedding")
                        assertEquals(192, values.length())
                        val vector = FloatArray(values.length()) { values.getDouble(it).toFloat() }
                        assertTrue(vector.all { it.isFinite() })
                        observations += SpeakerObservation(context.startMs, context.endMs, vector,
                            activity.quality, speechMs >= 800, activity.spans)
                        regions += context.region
                    }
                }
                val stable = SpeakerEvidence.stableSeeds(observations, regions)
                val result = SpeakerClusterer().cluster(stable)
                val predictions = SpeakerSpeechSupport.speakersForWords(words, stable, result.assignments)
                val cells = words.indices.map { index -> AttributionCell(readers[index], words[index].startMs,
                    words[index].endMs, predictions[index]) }
                val score = SpeakerAttributionScorer.score(cells)
                assertEquals(98670L, score.totalMs)
                val labels = predictions.filter { it != SpeakerClusterer.UNKNOWN }.distinct()
                val shortCells = cells.indices.filter { turnKinds[it] == "short" }.map { cells[it] }
                val shortMs = shortCells.sumOf { it.endMs - it.startMs }
                assertEquals(4800L, shortMs)
                // Reuse the full-session permutation; remapping only short replies could
                // incorrectly count a transient extra identity as the correct speaker.
                val shortCorrectMs = shortCells.filter { score.matching[it.reader] == it.predictedSpeakerId }
                    .sumOf { it.endMs - it.startMs }
                val diagnostic = "Russian fixed clip-time $condition: correctMs=${score.correctMs}, " +
                    "totalMs=${score.totalMs}, accuracy=${score.accuracy}, macro=${score.macroAccuracy}, " +
                    "unknown=${score.unknownRate}, speakers=${labels.size}, " +
                    "contexts=${contexts.size}, acceptedContexts=${observations.size}, " +
                    "shortReplyAccuracy=${shortCorrectMs.toDouble() / shortMs}"
                println(diagnostic)
                println("Russian fixed clip-time $condition per reader: ${score.perReaderAccuracy}")
                if (score.accuracy <= .75 || score.macroAccuracy <= .75) failures += diagnostic
                totalMs += score.totalMs
                correctMs += score.correctMs
                unknownMs += score.unknownMs
                macroAccuracySum += score.macroAccuracy
            } finally {
                observations.forEach { it.vector.fill(0f) }
                file.delete()
            }
        }
        assertEquals(setOf("clean", "gain035", "noise20db", "channel"), conditions)
        val pooledAccuracy = correctMs.toDouble() / totalMs
        val pooledMacroAccuracy = macroAccuracySum / cases.length()
        println("Russian fixed clip-time pooled: correctMs=$correctMs, totalMs=$totalMs, " +
            "accuracy=$pooledAccuracy, macro=$pooledMacroAccuracy, unknown=${unknownMs.toDouble() / totalMs}")
        println("Scope: continuous public synthetic sequences with reader-disjoint development/evaluation; " +
            "100ms clip-time probes, not real ASR words, speech-only DER, phone or natural-conversation accuracy.")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("Pooled clip-time accuracy must exceed 75%", pooledAccuracy > .75)
        assertTrue("Macro clip-time accuracy must exceed 75%", pooledMacroAccuracy > .75)
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
        val ids = predictions.filter { it != SpeakerClusterer.UNKNOWN }.distinct().sorted()
        val readers = examples.map { it.reader }.distinct()
        val matches = readers.map { reader ->
            ids.associateWith { id -> examples.indices.count { examples[it].reader == reader && predictions[it] == id } }
        }
        val memo = mutableMapOf<Pair<Int, Int>, Int>()
        fun bestMapping(index: Int, used: Int): Int {
            if (index == matches.size) return 0
            // Labels are bounded to 12. Subset DP scales to the expanded six-reader
            // corpus and permits an unmapped reference when a speaker was missed.
            return memo.getOrPut(index to used) {
                var best = bestMapping(index + 1, used)
                for ((label, id) in ids.withIndex()) if (used and (1 shl label) == 0) {
                    best = maxOf(best, matches[index].getValue(id) + bestMapping(index + 1, used or (1 shl label)))
                }
                best
            }
        }
        val errorRate = 1.0 - bestMapping(0, 0).toDouble() / examples.size
        val unknownRate = predictions.count { it == SpeakerClusterer.UNKNOWN }.toDouble() / examples.size
        val diagnostic = "Russian alternating readers: windows=${examples.size}, speakers=${ids.size}, " +
            "assignmentError=$errorRate, unknown=$unknownRate"
        println(diagnostic)
        if (ids.size != readers.size || errorRate > .15 || unknownRate > .15) {
            developmentCentroidDiagnostics(examples, "alternating")
        }
        assertEquals(diagnostic, readers.size, ids.size)
        assertTrue(diagnostic, errorRate <= 0.15)
        assertTrue(diagnostic, unknownRate <= 0.15)
    }

    /** Bounded calibration on DEVELOPMENT clips only; these candidates never touch evaluation. */
    private fun developmentCentroidDiagnostics(examples: List<Example>, scenario: String) {
        for (cutoff in listOf(.65f, .70f, .75f)) {
            val result = SpeakerClusterer(centroidThreshold = cutoff).cluster(examples.map { it.observation })
            val predictions = result.assignments.map { it.speakerId }
            // Unit-duration cells reproduce the existing window-count metric, including unknowns.
            val score = SpeakerAttributionScorer.score(examples.mapIndexed { index, item ->
                AttributionCell(item.reader, index.toLong(), index + 1L, predictions[index])
            })
            println("DEVELOPMENT centroid candidate $scenario cutoff=$cutoff: " +
                "accuracy=${score.accuracy}, unknown=${score.unknownRate}, " +
                "labels=${predictions.filter { it != SpeakerClusterer.UNKNOWN }.distinct().size}")
            for ((reader, indices) in examples.indices.groupBy { examples[it].reader }) {
                println("DEVELOPMENT candidate $scenario cutoff=$cutoff $reader: " +
                    indices.groupingBy { predictions[it] }.eachCount())
            }
            if (cutoff != .75f) continue
            // Assigned-window centers are diagnostics, not remembered speaker templates.
            val centers = examples.indices.filter { predictions[it] != SpeakerClusterer.UNKNOWN }
                .groupBy { predictions[it] }.mapValues { (_, indices) ->
                    val sum = FloatArray(examples.first().observation.vector.size)
                    for (index in indices) {
                        val vector = requireNotNull(SpeakerClusterer.normalized(examples[index].observation.vector))
                        for (dimension in sum.indices) sum[dimension] += vector[dimension]
                    }
                    requireNotNull(SpeakerClusterer.normalized(sum))
                }.toList()
            for (left in centers.indices) for (right in left + 1 until centers.size) {
                println("DEVELOPMENT assigned-centroid pair $scenario " +
                    "${centers[left].first}/${centers[right].first}: " +
                    SpeakerClusterer.dot(centers[left].second, centers[right].second))
            }
        }
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
        assertEquals(16, source.length())
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
            assertEquals(6, clips.map { it.reader }.distinct().size)
            assertEquals(setOf("clean", "gain035", "noise20db", "channel"), clips.map { it.variant }.toSet())
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
