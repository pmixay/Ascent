package com.pmixay.ascent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey
import kotlin.math.sqrt

/** A session-local, quality-filtered voice summary. Never contains an audio sample. */
data class VoiceSignature(
    val speakerId: String,
    val vector: FloatArray,
    val speechMs: Long,
    val windowCount: Int,
    val cohesion: Float,
)

/**
 * Learns only after the user names a voice. Automatic matches never train their own predictions.
 * Unconfirmed signatures stay in memory; confirmed contributions expire with their source text.
 * Similarity gates are conservative starting values, pending Russian device calibration.
 */
internal class VoiceProfileStore(
    root: File,
    keyProvider: () -> SecretKey = AndroidSecretKey::get,
) {
    private val encrypted = EncryptedJsonFile(File(root, "voice-profiles.enc"), keyProvider)
    private var loaded: VoiceMemory? = null

    @Synchronized fun match(
        conversationId: String,
        startedAtMs: Long,
        signatures: List<VoiceSignature>,
        retainedConversationIds: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<String, String> = memory().match(conversationId, startedAtMs, signatures, retainedConversationIds, nowMs)

    /** False means there was too little clean speech, or the unconfirmed session left memory. */
    @Synchronized fun remember(
        conversationId: String,
        speakerId: String,
        name: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = memory().remember(conversationId, speakerId, name, nowMs, ::persist)

    @Synchronized fun prune(retainedConversationIds: Set<String>, nowMs: Long = System.currentTimeMillis()) {
        memory().prune(retainedConversationIds, nowMs, ::persist)
    }

    @Synchronized fun discard(conversationId: String) { loaded?.discard(conversationId) }

    private fun memory(): VoiceMemory {
        loaded?.let { return it }
        val json = encrypted.read()
        val profiles = if (json == null) emptyList() else {
            require(json.getInt("schemaVersion") == 1) { "Неизвестный формат голосов" }
            // Embeddings from different speaker encoders cannot be compared or silently migrated.
            if (json.getString("embeddingModel") != EMBEDDING_MODEL) {
                encrypted.delete()
                emptyList()
            } else {
                val items = json.getJSONArray("profiles")
                require(items.length() <= MAX_PROFILES)
                List(items.length()) { index -> decodeProfile(items.getJSONObject(index)) }
            }
        }
        require(profiles.map { it.id }.distinct().size == profiles.size)
        return VoiceMemory(profiles).also { loaded = it }
    }

    private fun persist(profiles: List<VoiceProfile>) {
        encrypted.write(JSONObject().put("schemaVersion", 1).put("embeddingModel", EMBEDDING_MODEL)
            .put("profiles", JSONArray().apply { profiles.forEach { profile ->
                put(JSONObject().put("id", profile.id).put("name", profile.name)
                    .put("samples", JSONArray().apply { profile.samples.forEach { sample ->
                        put(JSONObject().put("conversationId", sample.conversationId).put("speakerId", sample.speakerId)
                            .put("startedAtMs", sample.startedAtMs).put("speechMs", sample.speechMs)
                            .put("windowCount", sample.windowCount).put("cohesion", sample.cohesion.toDouble())
                            .put("vector", JSONArray().apply { sample.vector.forEach { put(it.toDouble()) } }))
                    } }))
            } }))
    }

    private fun decodeProfile(json: JSONObject): VoiceProfile {
        val id = json.getString("id")
        val name = json.getString("name")
        require(id.isNotBlank() && id.length <= 100 && name.isNotBlank() && name.length <= 80)
        val samples = json.getJSONArray("samples")
        require(samples.length() in 1..MAX_SAMPLES_PER_PROFILE)
        return VoiceProfile(id, name, List(samples.length()) { index ->
            val sample = samples.getJSONObject(index)
            val vector = sample.getJSONArray("vector")
            require(vector.length() == EMBEDDING_DIMENSION)
            val signature = VoiceSignature(sample.getString("speakerId"),
                FloatArray(vector.length()) { vector.getDouble(it).toFloat() },
                sample.getLong("speechMs"), sample.getInt("windowCount"), sample.getDouble("cohesion").toFloat())
            val normalized = eligibleVector(signature)
            require(normalized != null) { "Некорректный голосовой профиль" }
            val conversationId = sample.getString("conversationId")
            val startedAtMs = sample.getLong("startedAtMs")
            require(conversationId.isNotBlank() && conversationId.length <= 100 && startedAtMs > 0)
            VoiceSample(conversationId, signature.speakerId, startedAtMs, normalized,
                signature.speechMs, signature.windowCount, signature.cohesion)
        })
    }
}

private const val EMBEDDING_MODEL = "eres2netv2-bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239"
private const val EMBEDDING_DIMENSION = 192
private const val MAX_PROFILES = 128
private const val MAX_SAMPLES_PER_PROFILE = 8
private const val MAX_PENDING_CONVERSATIONS = 64
private const val MATCH_THRESHOLD = 0.78f
private const val MATCH_MARGIN = 0.08f

internal data class VoiceSample(
    val conversationId: String,
    val speakerId: String,
    val startedAtMs: Long,
    val vector: FloatArray,
    val speechMs: Long,
    val windowCount: Int,
    val cohesion: Float,
)

internal data class VoiceProfile(val id: String, val name: String, val samples: List<VoiceSample>)

/** Pure matching/adaptation policy, separated from Android encryption for meaningful unit coverage. */
internal class VoiceMemory(initial: List<VoiceProfile> = emptyList()) {
    private var profiles = initial
    private data class Pending(val sample: VoiceSample, val suggestedProfileId: String?, val confirmedProfileId: String? = null)
    private data class Candidate(val speakerId: String, val profile: VoiceProfile, val score: Float)
    private val pending = linkedMapOf<String, Map<String, Pending>>()

    fun match(
        conversationId: String,
        startedAtMs: Long,
        signatures: List<VoiceSignature>,
        retainedConversationIds: Set<String>,
        nowMs: Long,
    ): Map<String, String> {
        require(conversationId.isNotBlank() && conversationId.length <= 100)
        if (!retained(startedAtMs, nowMs)) return emptyMap()
        val known = retainedProfiles(retainedConversationIds, nowMs)
        val centroids = known.mapNotNull { profile -> centroid(profile.samples)?.let { profile to it } }
        val samples = signatures.distinctBy { it.speakerId }.mapNotNull { signature ->
            eligibleVector(signature)?.let { vector -> VoiceSample(conversationId, signature.speakerId,
                startedAtMs, vector, signature.speechMs, signature.windowCount, signature.cohesion) }
        }
        val candidates = samples.mapNotNull { sample ->
            val scores = centroids.map { (profile, vector) -> Candidate(sample.speakerId, profile, cosine(sample.vector, vector)) }
                .sortedByDescending { it.score }
            val best = scores.firstOrNull() ?: return@mapNotNull null
            val runnerUp = scores.getOrNull(1)?.score ?: -1f
            best.takeIf { it.score >= MATCH_THRESHOLD && it.score - runnerUp >= MATCH_MARGIN }
        }
        // Different speakers in one conversation cannot silently collapse into one remembered voice.
        val assigned = candidates.groupBy { it.profile.id }.values.mapNotNull { group ->
            val ranked = group.sortedByDescending { it.score }
            ranked.first().takeIf { ranked.size == 1 || it.score - ranked[1].score >= MATCH_MARGIN }
        }.associateBy { it.speakerId }
        pending[conversationId] = samples.associate { sample ->
            sample.speakerId to Pending(sample, assigned[sample.speakerId]?.profile?.id)
        }
        while (pending.size > MAX_PENDING_CONVERSATIONS) pending.remove(pending.keys.first())
        return assigned.mapValues { it.value.profile.name }
    }

    fun remember(
        conversationId: String,
        speakerId: String,
        name: String,
        nowMs: Long,
        persist: (List<VoiceProfile>) -> Unit = {},
    ): Boolean {
        val clean = name.trim().take(80)
        val current = pending[conversationId]?.get(speakerId) ?: return false
        if (clean.isEmpty() || !retained(current.sample.startedAtMs, nowMs)) return false
        val existing = profiles.firstOrNull { it.id == current.confirmedProfileId }
            ?: profiles.firstOrNull { it.id == current.suggestedProfileId && it.name == clean }
        // A different entered name corrects a guess; it does not relabel or contaminate that old profile.
        val targetId = existing?.id ?: UUID.randomUUID().toString()
        val previous = existing?.samples.orEmpty().filter {
            retained(it.startedAtMs, nowMs) && !(it.conversationId == conversationId && it.speakerId == speakerId)
        }
        val updated = VoiceProfile(targetId, clean, (previous + current.sample)
            .sortedByDescending { it.startedAtMs }.take(MAX_SAMPLES_PER_PROFILE))
        val replacement = (profiles.filter { it.id != targetId } + updated)
            .sortedByDescending { it.samples.maxOf { sample -> sample.startedAtMs } }.take(MAX_PROFILES)
        // Commit before changing memory so an I/O failure cannot teach a phantom profile.
        persist(replacement)
        profiles = replacement
        pending[conversationId] = pending.getValue(conversationId) +
            (speakerId to current.copy(confirmedProfileId = targetId))
        return true
    }

    fun prune(retainedConversationIds: Set<String>, nowMs: Long, persist: (List<VoiceProfile>) -> Unit = {}) {
        val replacement = retainedProfiles(retainedConversationIds, nowMs)
        if (replacement != profiles) {
            persist(replacement)
            profiles = replacement
        }
        pending.entries.removeAll { (id, voices) -> id !in retainedConversationIds ||
            voices.values.none { retained(it.sample.startedAtMs, nowMs) } }
    }

    fun discard(conversationId: String) { pending.remove(conversationId) }

    private fun retainedProfiles(ids: Set<String>, nowMs: Long): List<VoiceProfile> = profiles.mapNotNull { profile ->
        val samples = profile.samples.filter { it.conversationId in ids && retained(it.startedAtMs, nowMs) }
        if (samples.isEmpty()) null else profile.copy(samples = samples)
    }
}

private fun retained(startedAtMs: Long, nowMs: Long): Boolean =
    startedAtMs > 0 && startedAtMs <= nowMs && startedAtMs > nowMs - HISTORY_RETENTION_MS

private fun eligibleVector(signature: VoiceSignature): FloatArray? {
    if (signature.speakerId.isBlank() || signature.speakerId.length > 100 || signature.speakerId == "speaker_unknown" ||
        signature.speechMs < 6000 || signature.windowCount < 3 || !signature.cohesion.isFinite() ||
        signature.cohesion !in 0.80f..1.0001f) return null
    return normalized(signature.vector)
}

private fun normalized(vector: FloatArray): FloatArray? {
    if (vector.size != EMBEDDING_DIMENSION || vector.any { !it.isFinite() }) return null
    val norm = sqrt(vector.sumOf { it.toDouble() * it })
    if (!norm.isFinite() || norm < 1e-8) return null
    return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
}

private fun centroid(samples: List<VoiceSample>): FloatArray? {
    val total = FloatArray(EMBEDDING_DIMENSION)
    // Equal contribution per confirmed conversation prevents one long session dominating a voice.
    samples.groupBy { it.conversationId }.values.forEach { conversationSamples ->
        val session = FloatArray(EMBEDDING_DIMENSION)
        conversationSamples.forEach { sample -> for (i in session.indices) session[i] += sample.vector[i] }
        normalized(session)?.let { vector -> for (i in total.indices) total[i] += vector[i] }
    }
    return normalized(total)
}

private fun cosine(a: FloatArray, b: FloatArray): Float =
    a.indices.sumOf { a[it].toDouble() * b[it] }.toFloat().coerceIn(-1f, 1f)
