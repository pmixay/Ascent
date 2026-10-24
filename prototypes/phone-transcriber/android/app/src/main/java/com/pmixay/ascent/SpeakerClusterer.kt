package com.pmixay.ascent

import kotlin.math.sqrt

/** Session-local cosine clustering. Threshold must be calibrated on real conversations. */
class SpeakerClusterer(private val threshold: Float = 0.65f, private val maxSpeakers: Int = 12) {
    private data class Cluster(val sum: FloatArray, var count: Int)
    private val clusters = mutableListOf<Cluster>()

    fun add(embedding: FloatArray): String {
        val vector = normalized(embedding) ?: return UNKNOWN
        val scores = clusters.map { cosine(vector, it.sum) }
        val best = scores.indices.maxByOrNull { scores[it] }
        if (best == null || scores[best] < threshold) {
            if (clusters.size >= maxSpeakers) return UNKNOWN
            clusters += Cluster(vector.copyOf(), 1)
            return "speaker_${clusters.size}"
        }
        val cluster = clusters[best]
        for (i in vector.indices) cluster.sum[i] += vector[i]
        cluster.count++
        return "speaker_${best + 1}"
    }

    fun classify(embedding: FloatArray): String {
        val vector = normalized(embedding) ?: return UNKNOWN
        val scores = clusters.map { cosine(vector, it.sum) }
        val best = scores.indices.maxByOrNull { scores[it] } ?: return UNKNOWN
        return if (scores[best] >= threshold) "speaker_${best + 1}" else UNKNOWN
    }

    companion object {
        const val UNKNOWN = "speaker_unknown"
        private fun normalized(vector: FloatArray): FloatArray? {
            if (vector.isEmpty() || vector.any { !it.isFinite() }) return null
            val norm = sqrt(vector.sumOf { it.toDouble() * it }.toFloat())
            if (norm < 1e-8f) return null
            return FloatArray(vector.size) { vector[it] / norm }
        }
        private fun cosine(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size)
            val unit = normalized(b) ?: return -1f
            return a.indices.sumOf { a[it].toDouble() * unit[it] }.toFloat()
        }
    }
}
