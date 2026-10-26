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
        var totalMs = 0L
        var strictCorrectMs = 0L
        var completedCorrectMs = 0L
        var inferredMs = 0L
        var wordsTotal = 0L
        var wordErrors = 0L
        var charTotal = 0L
        var charErrors = 0L
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
            val words = document.getJSONArray("words").timedWords()
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
                    verifyRecognitionMirror(wave, document.getJSONArray("asr_windows"), tokens, words)
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
                val asr = document.getJSONObject("asr_metrics")
                assertEquals(asr.getInt("words"), expectedTokens.size)
                assertEquals(asr.getInt("word_errors"), SpeakerRecognitionMetrics.distance(expectedTokens, actualTokens))
                assertEquals(expectedTokens.size, joint.referenceWords)
                val strictLabels = SpeakerSpeechSupport.speakersForWords(probes, stable, clustered.assignments)
                val completeProbes = SpeakerAttribution.complete(probes, stable, clustered)
                fun score(labels: List<String>) = SpeakerAttributionScorer.score(probes.indices.map { p ->
                    AttributionCell(readers[p], probes[p].startMs, probes[p].endMs, labels[p])
                })
                val strictScore = score(strictLabels)
                val fullScore = score(completeProbes.speakerIds)
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
                charTotal += asr.getLong("chars")
                charErrors += asr.getLong("char_errors")
                jointErrors += joint.errors
                predictedWords += words.size
                inferredWords += completed.inferred.count { it }
            } finally { observations.forEach { it.vector.fill(0f) } }
        }
        assertEquals(1134, sources.size)
        assertEquals(index.getInt("source_files"), sources.size)
        assertEquals(index.getLong("source_duration_ms"), totalMs)
        assertTrue("At least two hours of unique source audio are required", totalMs >= 7200000)
        assertEquals(setOf("M", "D", "S"), actorTotals.keys)
        val asrTotals = index.getJSONObject("asr_metrics")
        assertEquals(asrTotals.getLong("words"), wordsTotal)
        assertEquals(asrTotals.getLong("word_errors"), wordErrors)
        assertEquals(asrTotals.getLong("chars"), charTotal)
        assertEquals(asrTotals.getLong("char_errors"), charErrors)
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
        println("Scope: fixed acted Russian dialogue clips in original order with synthetic gaps; " +
            "clip-time speaker backend probes and end-to-end cpWER, not natural-conversation DER or phone accuracy. " +
            "Reference boundaries never select inference windows. Unknown/missing evidence remains in the denominator. " +
            "Inferred assignments are scored, not treated as confirmed identities. No weights or profiles were trained.")
    }

    private fun verifyRecognitionMirror(wave: PcmWave, windows: JSONArray, tokens: List<String>, expected: List<TimedWord>) {
        var start = 0L
        val actual = mutableListOf<TimedWord>()
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
            start = window.coreEnd
        }
        assertEquals(wave.sampleCount, start)
        assertTrue("Host CTC/ownership mirror diverged from Android", expected == actual.sortedBy { it.startMs })
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
