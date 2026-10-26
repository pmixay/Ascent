package com.pmixay.ascent

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Dedicated CI job only. Public dialogue resources never enter an APK or report artifact. */
class TwoHourSpeechEvaluationTest {
    @Test fun evaluateFrozenRussianDialoguesWithTheProductionAttributionPolicy() {
        val location = System.getenv("ASCENT_DIALOG_EVALUATION")
        assumeTrue("The two-hour evaluation runs in its dedicated CI job", !location.isNullOrBlank())
        val indexFile = File(requireNotNull(location)).canonicalFile
        val root = requireNotNull(indexFile.parentFile)
        val index = JSONObject(indexFile.readText())
        assertEquals(1, index.getInt("schema_version"))
        assertEquals("bb11205cc6ce6b28c601aa09173eba2e5b88d5e94394f2f4bfb27834a4c93c73",
            index.getString("source_manifest_sha256"))
        val provenance = index.getJSONObject("provenance")
        assertEquals("9e423f2690bfaf3e0fbb81185a080bfff27c4ec5aacef5e615676fffbe2545ff", provenance.getString("asr_sha256"))
        assertEquals("bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239", provenance.getString("speaker_sha256"))
        val tokens = index.getJSONArray("tokens").strings()
        val sessions = index.getJSONArray("sessions")
        val sources = mutableSetOf<String>()
        val actorTotals = sortedMapOf<String, LongArray>()
        val subsetTotals = sortedMapOf<String, MutableMap<String, Long>>()
        var totalMs = 0L
        var strictCorrectMs = 0L
        var completedCorrectMs = 0L
        var inferredMs = 0L
        var wordsTotal = 0L
        var wordErrors = 0L
        var cachedWordErrors = 0L
        var charTotal = 0L
        var charErrors = 0L
        var cachedCharErrors = 0L
        var jointErrors = 0L
        var predictedWords = 0L
        var inferredWords = 0L
        var extraLabels = 0
        var missingLabels = 0
        for (sessionIndex in 0 until sessions.length()) {
            val descriptor = sessions.getJSONObject(sessionIndex)
            val document = JSONObject(child(root, descriptor.getString("file")).readText())
            val id = document.getString("id")
            assertEquals(descriptor.getString("id"), id)
            val references = document.getJSONArray("references")
            val cachedWords = document.getJSONArray("words").timedWords()
            var words = cachedWords
            val probes = mutableListOf<TimedWord>()
            val readers = mutableListOf<String>()
            val referenceTurns = mutableListOf<AttributedText>()
            var sourceMs = 0L
            var previousEnd = 0L
            for (r in 0 until references.length()) {
                val reference = references.getJSONObject(r)
                assertTrue("Source utterances must be unique", sources.add(reference.getString("source_id")))
                val start = reference.getLong("start_ms")
                val end = reference.getLong("end_ms")
                assertTrue(start >= previousEnd && end > start && end <= document.getLong("duration_ms"))
                previousEnd = end
                sourceMs += end - start
                val speaker = reference.getString("speaker")
                referenceTurns += AttributedText(speaker, reference.getString("text"))
                var position = start
                while (position < end) {
                    val next = minOf(position + 100, end)
                    probes += TimedWord("reference", position, next)
                    readers += speaker
                    position = next
                }
            }
            assertEquals(document.getLong("source_duration_ms"), sourceMs)
            val audio = child(root, document.getString("audio_file"))
            assertEquals(document.getString("audio_sha256"), sha256(audio))
            val observations = mutableListOf<SpeakerObservation>()
            val regions = mutableListOf<Int>()
            try {
                PcmWave(audio).use { wave ->
                    assertEquals(document.getLong("duration_ms"), wave.durationMs)
                    words = verifyRecognitionMirror(wave, document.getJSONArray("asr_windows"), tokens, cachedWords)
                    val contexts = SpeakerSpeechSupport.contexts(words, wave.durationMs)
                    val supplied = document.getJSONArray("contexts")
                    assertEquals("Embedding context mirror diverged in $id", contexts.size, supplied.length())
                    for ((c, context) in contexts.withIndex()) {
                        val record = supplied.getJSONObject(c)
                        assertEquals(context.startMs, record.getLong("start_ms"))
                        assertEquals(context.endMs, record.getLong("end_ms"))
                        assertEquals(context.region, record.getInt("region"))
                        val samples = wave.read(context.startMs * 16, ((context.endMs - context.startMs) * 16).toInt())
                        val activity = try { SpeakerSpeechSupport.activity(samples, context.startMs) }
                            finally { samples.fill(0f) }
                        val speechMs = activity.spans.sumOf { it.endMs - it.startMs }
                        if (speechMs < 320 || activity.quality < .20f || record.isNull("embedding")) continue
                        val values = record.getJSONArray("embedding")
                        assertEquals(192, values.length())
                        val vector = FloatArray(values.length()) { values.getDouble(it).toFloat() }
                        assertTrue(vector.all { it.isFinite() })
                        observations += SpeakerObservation(context.startMs, context.endMs, vector,
                            activity.quality, speechMs >= 800, activity.spans)
                        regions += context.region
                    }
                }
                val stable = SpeakerEvidence.stableSeeds(observations, regions)
                val clustered = SpeakerClusterer().cluster(stable)
                val completed = SpeakerAttribution.complete(words, stable, clustered)
                assertEquals(words.size, completed.speakerIds.size)
                assertTrue(completed.speakerIds.none { it == SpeakerClusterer.UNKNOWN || it.isBlank() })
                val hypothesis = words.indices.map { AttributedText(completed.speakerIds[it], words[it].text) }
                val joint = SpeakerRecognitionMetrics.cpwer(referenceTurns, hypothesis)
                val expectedTokens = referenceTurns.flatMap { SpeakerRecognitionMetrics.words(it.text) }
                val actualTokens = hypothesis.flatMap { SpeakerRecognitionMetrics.words(it.text) }
                val cachedAsr = document.getJSONObject("asr_metrics")
                val cachedTokens = cachedWords.flatMap { SpeakerRecognitionMetrics.words(it.text) }
                val referenceCharacters = characters(expectedTokens)
                assertEquals(cachedAsr.getInt("words"), expectedTokens.size)
                assertEquals(cachedAsr.getInt("word_errors"), SpeakerRecognitionMetrics.distance(expectedTokens, cachedTokens))
                assertEquals(cachedAsr.getInt("chars"), referenceCharacters.size)
                assertEquals(cachedAsr.getInt("char_errors"), SpeakerRecognitionMetrics.distance(referenceCharacters, characters(cachedTokens)))
                val asr = JSONObject().put("words", expectedTokens.size)
                    .put("word_errors", SpeakerRecognitionMetrics.distance(expectedTokens, actualTokens))
                    .put("chars", referenceCharacters.size)
                    .put("char_errors", SpeakerRecognitionMetrics.distance(referenceCharacters, characters(actualTokens)))
                assertEquals(expectedTokens.size, joint.referenceWords)
                val strictLabels = SpeakerSpeechSupport.speakersForWords(probes, stable, clustered.assignments)
                val completeProbes = SpeakerAttribution.complete(probes, stable, clustered)
                fun score(labels: List<String>) = SpeakerAttributionScorer.score(probes.indices.map { p ->
                    AttributionCell(readers[p], probes[p].startMs, probes[p].endMs, labels[p])
                })
                val strictScore = score(strictLabels)
                val fullScore = score(completeProbes.speakerIds)
                if (id.startsWith("masha_dima_part1")) {
                    AsrErrorDiagnostics.printDevelopment(document, tokens)
                    printDevelopmentSpeakerDiagnostics(id, references, stable, clustered.assignments,
                        words, completed, probes, completeProbes.speakerIds, fullScore.matching)
                }
                assertEquals(sourceMs, fullScore.totalMs)
                val sessionInferred = probes.indices.filter { completeProbes.inferred[it] }
                    .sumOf { probes[it].endMs - probes[it].startMs }
                val actualReaders = readers.distinct().size
                val labels = completeProbes.speakerIds.distinct().size
                extraLabels += maxOf(0, labels - actualReaders)
                missingLabels += maxOf(0, actualReaders - labels)
                for ((reader, cells) in probes.indices.groupBy { readers[it] }) {
                    val totals = actorTotals.getOrPut(reader) { LongArray(4) }
                    for (p in cells) {
                        val duration = probes[p].endMs - probes[p].startMs
                        totals[0] += duration
                        if (fullScore.matching[reader] == completeProbes.speakerIds[p]) totals[1] += duration
                        if (strictScore.matching[reader] == strictLabels[p]) totals[2] += duration
                        if (completeProbes.inferred[p]) totals[3] += duration
                    }
                }
                println("DIALOG EVALUATION $id: sourceMs=$sourceMs, referenceSpeakers=$actualReaders, " +
                    "predictedSpeakers=$labels, referenceWords=${joint.referenceWords}, " +
                    "wordErrors=${asr.getInt("word_errors")}, WER=${asr.getDouble("word_errors") / joint.referenceWords}, " +
                    "cpWordErrors=${joint.errors}, cpWER=${joint.rate}, strictSpeakerAccuracy=${strictScore.accuracy}, " +
                    "completedSpeakerAccuracy=${fullScore.accuracy}, inferredClipTime=${sessionInferred.toDouble() / sourceMs}, " +
                    "recognizedWords=${words.size}, inferredRecognizedWords=${completed.inferred.count { it }}")
                println("DIALOG EVALUATION $id per actor: ${fullScore.perReaderAccuracy}")
                totalMs += sourceMs
                strictCorrectMs += strictScore.correctMs
                completedCorrectMs += fullScore.correctMs
                inferredMs += sessionInferred
                wordsTotal += joint.referenceWords
                wordErrors += asr.getLong("word_errors")
                cachedWordErrors += cachedAsr.getLong("word_errors")
                charTotal += asr.getLong("chars")
                charErrors += asr.getLong("char_errors")
                cachedCharErrors += cachedAsr.getLong("char_errors")
                jointErrors += joint.errors
                predictedWords += words.size
                inferredWords += completed.inferred.count { it }
                val subset = if (id.startsWith("masha_dima_part1")) "development" else "validation"
                val tally = subsetTotals.getOrPut(subset) { linkedMapOf() }
                val values = linkedMapOf("sourceMs" to sourceMs, "uniqueFiles" to references.length().toLong(),
                    "sessions" to 1L, "referenceWords" to joint.referenceWords.toLong(),
                    "wordErrors" to asr.getLong("word_errors"), "referenceChars" to asr.getLong("chars"),
                    "charErrors" to asr.getLong("char_errors"), "cpWordErrors" to joint.errors.toLong(),
                    "strictCorrectMs" to strictScore.correctMs, "completedCorrectMs" to fullScore.correctMs,
                    "inferredMs" to sessionInferred, "recognizedWords" to words.size.toLong(),
                    "inferredRecognizedWords" to completed.inferred.count { it }.toLong(),
                    "extraSpeakerLabels" to maxOf(0, labels - actualReaders).toLong(),
                    "missingSpeakerLabels" to maxOf(0, actualReaders - labels).toLong())
                values.forEach { (key, value) -> tally[key] = tally.getOrDefault(key, 0L) + value }
            } finally { observations.forEach { it.vector.fill(0f) } }
        }
        assertEquals(1134, sources.size)
        assertEquals(index.getInt("source_files"), sources.size)
        assertEquals(index.getLong("source_duration_ms"), totalMs)
        assertTrue("At least two hours of unique source audio are required", totalMs >= 7200000)
        assertTrue("Strict speaker attribution must exceed 75% on the fixed corpus",
            strictCorrectMs.toDouble() / totalMs > 0.75)
        assertTrue("Completed speaker attribution must exceed 75% on the fixed corpus",
            completedCorrectMs.toDouble() / totalMs > 0.75)
        assertEquals(setOf("M", "D", "S"), actorTotals.keys)
        val asrTotals = index.getJSONObject("asr_metrics")
        assertEquals(asrTotals.getLong("words"), wordsTotal)
        assertEquals(asrTotals.getLong("word_errors"), cachedWordErrors)
        assertEquals(asrTotals.getLong("chars"), charTotal)
        assertEquals(asrTotals.getLong("char_errors"), cachedCharErrors)
        println("DIALOG EVALUATION TOTAL: sourceMs=$totalMs, uniqueFiles=${sources.size}, sessions=${sessions.length()}, " +
            "referenceWords=$wordsTotal, wordErrors=$wordErrors, WER=${wordErrors.toDouble() / wordsTotal}, " +
            "referenceChars=$charTotal, charErrors=$charErrors, CER=${charErrors.toDouble() / charTotal}, " +
            "cpWordErrors=$jointErrors, cpWER=${jointErrors.toDouble() / wordsTotal}, " +
            "strictCorrectMs=$strictCorrectMs, completedCorrectMs=$completedCorrectMs, " +
            "strictSpeakerAccuracy=${strictCorrectMs.toDouble() / totalMs}, " +
            "completedSpeakerAccuracy=${completedCorrectMs.toDouble() / totalMs}, " +
            "inferredClipTime=${inferredMs.toDouble() / totalMs}, recognizedWords=$predictedWords, " +
            "inferredRecognizedWords=$inferredWords, extraSpeakerLabels=$extraLabels, missingSpeakerLabels=$missingLabels")
        actorTotals.forEach { (actor, t) -> println("DIALOG EVALUATION actor=$actor: sourceMs=${t[0]}, " +
            "correctMs=${t[1]}, completedAccuracy=${t[1].toDouble() / t[0]}, strictAccuracy=${t[2].toDouble() / t[0]}, " +
            "inferredClipTime=${t[3].toDouble() / t[0]}") }
        subsetTotals.forEach { (subset, tally) ->
            fun rate(numerator: String, denominator: String) = tally.getValue(numerator).toDouble() / tally.getValue(denominator)
            println("DIALOG EVALUATION SUBSET $subset: ${tally.entries.joinToString { "${it.key}=${it.value}" }}, " +
                "WER=${rate("wordErrors", "referenceWords")}, CER=${rate("charErrors", "referenceChars")}, " +
                "cpWER=${rate("cpWordErrors", "referenceWords")}, " +
                "strictSpeakerAccuracy=${rate("strictCorrectMs", "sourceMs")}, " +
                "completedSpeakerAccuracy=${rate("completedCorrectMs", "sourceMs")}, " +
                "inferredClipTime=${rate("inferredMs", "sourceMs")}")
        }
        println("Scope: fixed acted Russian dialogue clips in original order with synthetic gaps; " +
            "clip-time speaker backend probes and end-to-end cpWER, not natural-conversation DER or phone accuracy. " +
            "Reference boundaries never select inference windows. Unknown/missing evidence remains in the denominator. " +
            "Inferred assignments are scored, not treated as confirmed identities. No weights or profiles were trained.")
    }

    /** Reference labels diagnose completed predictions only; they never enter production inference. */
    private fun printDevelopmentSpeakerDiagnostics(
        id: String,
        references: JSONArray,
        windows: List<SpeakerObservation>,
        assignments: List<SpeakerAssignment>,
        words: List<TimedWord>,
        completed: CompletedSpeakerAttribution,
        probes: List<TimedWord>,
        probeLabels: List<String>,
        matching: Map<String, String>,
    ) {
        data class Reference(val speaker: String, val startMs: Long, val endMs: Long)
        val turns = (0 until references.length()).map { index -> references.getJSONObject(index).let {
            Reference(it.getString("speaker"), it.getLong("start_ms"), it.getLong("end_ms"))
        } }
        fun union(spans: List<SpeechSpan>): List<SpeechSpan> {
            val result = mutableListOf<SpeechSpan>()
            for (span in spans.sortedBy { it.startMs }) {
                val previous = result.lastOrNull()
                if (previous != null && span.startMs <= previous.endMs)
                    result[result.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, span.endMs))
                else result += span
            }
            return result
        }
        fun overlap(spans: List<SpeechSpan>): Map<String, Long> = turns.groupBy { it.speaker }.mapValues { (_, values) ->
            values.sumOf { turn -> spans.sumOf { span ->
                (minOf(turn.endMs, span.endMs) - maxOf(turn.startMs, span.startMs)).coerceAtLeast(0)
            } }
        }
        fun quality(value: Float) = when {
            value < .35f -> "low"
            value < .55f -> "medium"
            value < .75f -> "high"
            else -> "veryHigh"
        }
        // Overlap weighting here is a diagnostic of each context, not a new accuracy denominator.
        // A context can span two real voices even when its energy occupancy is very high.
        val referenceSupport = windows.map { overlap(union(it.speech)) }
        for ((bucket, indices) in windows.indices.groupBy { "seed=${windows[it].canSeed},quality=${quality(windows[it].quality)}" }.toSortedMap()) {
            val supportedMs = indices.sumOf { referenceSupport[it].values.sum() }
            val dominantMs = indices.sumOf { referenceSupport[it].values.maxOrNull() ?: 0 }
            val mixed = indices.count { index ->
                val support = referenceSupport[index].values.sum()
                support > 0 && (referenceSupport[index].values.maxOrNull() ?: 0).toDouble() / support < .95
            }
            val unknown = indices.count { assignments[it].speakerId == SpeakerClusterer.UNKNOWN }
            println("DIALOG DEVELOPMENT WINDOWS $id $bucket: count=${indices.size}, mixed=$mixed, " +
                "unknown=$unknown, overlappingContextSupportMs=$supportedMs, " +
                "dominantReferencePurity=${if (supportedMs == 0L) 0.0 else dominantMs.toDouble() / supportedMs}")
        }
        // Union support per output cluster avoids counting overlapping windows as extra evidence.
        for ((label, indices) in assignments.indices.groupBy { assignments[it].speakerId }.toSortedMap()) {
            val support = overlap(union(indices.flatMap { windows[it].speech }))
            println("DIALOG DEVELOPMENT CLUSTER $id label=$label: windows=${indices.size}, " +
                "seeds=${indices.count { windows[it].canSeed }}, " +
                "meanQuality=${indices.map { windows[it].quality.toDouble() }.average()}, " +
                "referenceSupportMs=$support, matchedReference=${matching.entries.firstOrNull { it.value == label }?.key ?: "none"}")
        }
        // Inspect frozen output cohorts, not hidden discovery groups. These diagnostic means
        // approximate the candidate's available parent evidence; they never alter a label.
        val vectors = windows.map { SpeakerClusterer.normalized(it.vector) }
        val cohorts = assignments.indices.filter { assignments[it].speakerId != SpeakerClusterer.UNKNOWN }
            .groupBy { assignments[it].speakerId }
        val centers = cohorts.mapValues { (_, indices) ->
            val values = indices.mapNotNull { vectors[it] }
            if (values.isEmpty()) null else {
                val sum = FloatArray(values.first().size)
                values.forEach { vector -> for (component in sum.indices) sum[component] += vector[component] }
                try { SpeakerClusterer.normalized(sum) } finally { sum.fill(0f) }
            }
        }
        try {
            val order = windows.indices.sortedWith(compareBy({ windows[it].startMs }, { windows[it].endMs }, { it }))
            val positions = order.withIndex().associate { it.value to it.index }
            val supports = cohorts.mapValues { (_, indices) -> union(indices.flatMap { windows[it].speech })
                .sumOf { it.endMs - it.startMs } }
            fun independentMatch(vector: FloatArray, indices: List<Int>): Float? {
                val ranked = indices.filter { windows[it].canSeed && windows[it].quality >= .55f && vectors[it] != null }
                    .map { it to SpeakerClusterer.dot(vector, vectors[it]!!) }.sortedByDescending { it.second }
                for (position in ranked.indices) {
                    val current = windows[ranked[position].first]
                    if ((0 until position).any { prior ->
                        val previous = windows[ranked[prior].first]
                        current.startMs >= previous.endMs || previous.startMs >= current.endMs
                    }) return ranked[position].second
                }
                return null
            }
            for ((label, indices) in cohorts.toSortedMap()) {
                val support = supports.getValue(label)
                if (support >= 6000) continue
                val parents = cohorts.keys.filter { it != label && supports.getValue(it) >= maxOf(4000, support * 2) }
                for (index in indices.take(8)) {
                    val vector = vectors[index] ?: continue
                    val scores = parents.mapNotNull { parent -> centers[parent]?.let { center ->
                        Triple(parent, SpeakerClusterer.dot(vector, center), independentMatch(vector, cohorts.getValue(parent)))
                    } }.sortedByDescending { it.second }.take(3)
                    val position = positions.getValue(index)
                    val before = order.subList(0, position).lastOrNull {
                        assignments[it].speakerId != label && assignments[it].speakerId != SpeakerClusterer.UNKNOWN }
                    val after = order.subList(position + 1, order.size).firstOrNull {
                        assignments[it].speakerId != label && assignments[it].speakerId != SpeakerClusterer.UNKNOWN }
                    val previous = before?.let { "${assignments[it].speakerId}@gap${windows[index].startMs - windows[it].endMs}" } ?: "none"
                    val next = after?.let { "${assignments[it].speakerId}@gap${windows[it].startMs - windows[index].endMs}" } ?: "none"
                    println("DIALOG DEVELOPMENT SMALL CLUSTER $id label=$label: " +
                        "startMs=${windows[index].startMs}, endMs=${windows[index].endMs}, " +
                        "quality=${windows[index].quality}, canSeed=${windows[index].canSeed}, " +
                        "groupSpeechMs=$support, previous=$previous, next=$next, " +
                        "assignedParentMeanAndIndependentScores=${scores.joinToString { "${it.first}:${it.second}/${it.third}" }}")
                }
            }
        } finally {
            vectors.forEach { it?.fill(0f) }
            centers.values.forEach { it?.fill(0f) }
        }
        var splitProbeTurns = 0
        var splitRecognizedTurns = 0
        var emptyRecognizedTurns = 0
        var probeSwitches = 0
        var wordSwitches = 0
        var maximumProbeLabels = 0
        for (turn in turns) {
            val localProbeLabels = probes.indices.filter {
                val midpoint = probes[it].startMs + (probes[it].endMs - probes[it].startMs) / 2
                midpoint >= turn.startMs && midpoint < turn.endMs
            }.map { probeLabels[it] }
            val localWordLabels = words.indices.filter {
                val midpoint = words[it].startMs + (words[it].endMs - words[it].startMs) / 2
                midpoint >= turn.startMs && midpoint < turn.endMs
            }.map { completed.speakerIds[it] }
            val probeLabelCount = localProbeLabels.distinct().size
            maximumProbeLabels = maxOf(maximumProbeLabels, probeLabelCount)
            if (probeLabelCount > 1) splitProbeTurns++
            if (localWordLabels.distinct().size > 1) splitRecognizedTurns++
            if (localWordLabels.isEmpty()) emptyRecognizedTurns++
            probeSwitches += localProbeLabels.zipWithNext().count { (left, right) -> left != right }
            wordSwitches += localWordLabels.zipWithNext().count { (left, right) -> left != right }
        }
        println("DIALOG DEVELOPMENT FRAGMENTATION $id: referenceTurns=${turns.size}, " +
            "splitProbeTurns=$splitProbeTurns, probeSwitches=$probeSwitches, maximumProbeLabels=$maximumProbeLabels, " +
            "splitRecognizedTurns=$splitRecognizedTurns, wordSwitches=$wordSwitches, " +
            "emptyRecognizedTurns=$emptyRecognizedTurns, inferredRecognizedWords=${completed.inferred.count { it }}")
    }

    private fun characters(tokens: List<String>) = tokens.joinToString("").codePoints().toArray().map { it.toString() }

    private fun verifyRecognitionMirror(wave: PcmWave, windows: JSONArray, tokens: List<String>, expected: List<TimedWord>): List<TimedWord> {
        var start = 0L
        val actual = mutableListOf<TimedWord>()
        val stitcher = CtcChunkStitcher()
        for (i in 0 until windows.length()) {
            val record = windows.getJSONObject(i)
            val window = AsrChunking.next(start, wave.sampleCount, wave::read)
            assertEquals(window.coreStart, record.getLong("core_start"))
            assertEquals(window.coreEnd, record.getLong("core_end"))
            assertEquals(window.start, record.getLong("start"))
            assertEquals(window.end, record.getLong("end"))
            val frames = record.getJSONArray("ids")
            val ids = IntArray(frames.length()) { frames.getInt(it) }
            val decoded = CtcDecoder.decode(ids, tokens, tokens.size, window.start * 1000 / 16000,
                maxOf(window.end - window.start, 4000) * 1000 / 16000)
                .filter { it.startMs < wave.durationMs }
                .map { it.copy(endMs = minOf(it.endMs, wave.durationMs)) }
            actual += CtcDecoder.ownedBy(decoded, window.coreStart * 1000 / 16000, window.coreEnd * 1000 / 16000)
            stitcher.append(decoded, window.coreStart * 1000 / 16000, window.coreEnd * 1000 / 16000,
                window.start * 1000 / 16000, window.end * 1000 / 16000)
            start = window.coreEnd
        }
        assertEquals(wave.sampleCount, start)
        assertTrue("Host CTC/ownership mirror diverged from Android", expected == actual.sortedBy { it.startMs })
        val result = stitcher.finish()
        assertEquals("Stitching must preserve the exact acoustic support used by cached embeddings",
            SpeakerSpeechSupport.contexts(expected, wave.durationMs), SpeakerSpeechSupport.contexts(result, wave.durationMs))
        return result
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.timedWords() = (0 until length()).map { getJSONObject(it).let { item ->
        TimedWord(item.getString("text"), item.getLong("start_ms"), item.getLong("end_ms"))
    } }
    private fun child(root: File, name: String): File = File(root, name).canonicalFile.also {
        require(it.parentFile == root && it.isFile) { "Invalid evaluation resource" }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
