package com.pmixay.ascent

/**
 * Reconcile the same emission decoded on both sides of an ASR chunk seam.
 * Midpoint ownership alone can retain both copies when contextual decoding moves
 * their estimated times across the seam. Repeated or conflicting speech is kept.
 */
internal class CtcChunkStitcher {
    private data class Previous(
        val decoded: List<TimedWord>, val startMs: Long, val endMs: Long,
        val coreEndMs: Long, val ownedTail: TimedWord?,
    )

    private val output = mutableListOf<TimedWord>()
    private var previous: Previous? = null

    fun append(
        decoded: List<TimedWord>, coreStartMs: Long, coreEndMs: Long,
        windowStartMs: Long, windowEndMs: Long,
    ) {
        // Sample-domain progress can leave a final core shorter than one millisecond.
        require(windowStartMs <= coreStartMs && coreStartMs <= coreEndMs && coreEndMs <= windowEndMs)
        val before = previous
        require(before == null || before.coreEndMs == coreStartMs) { "ASR cores must be contiguous" }
        val owned = CtcDecoder.ownedBy(decoded, coreStartMs, coreEndMs)
        val left = before?.ownedTail?.takeIf { it == output.lastOrNull() }
        val right = owned.firstOrNull()
        var consumed = 0
        if (before != null && left != null && right != null && left.text == right.text && overlaps(left, right)) {
            val sharedStart = maxOf(before.startMs, windowStartMs)
            val sharedEnd = minOf(before.endMs, windowEndMs)
            fun uniqueEvidence(words: List<TimedWord>) = words.filter {
                it.text == left.text && it.startMs < sharedEnd && it.endMs > sharedStart
            }.singleOrNull()
            val leftEvidence = uniqueEvidence(before.decoded)
            val rightEvidence = uniqueEvidence(decoded)
            val mergedStart = minOf(left.startMs, right.startMs)
            val predecessor = output.getOrNull(output.lastIndex - 1)
            // Each contextual decode must show exactly one occurrence in the overlap.
            // An intentional repetition such as "да да" therefore remains untouched.
            if (sharedStart < sharedEnd && leftEvidence != null && rightEvidence != null &&
                overlaps(leftEvidence, rightEvidence) &&
                (predecessor == null || mergedStart >= predecessor.startMs)) {
                // The union preserves the complete ASR-supported acoustic span; speaker
                // context extraction sees exactly the same support before and after repair.
                output[output.lastIndex] = left.copy(startMs = mergedStart,
                    endMs = maxOf(left.endMs, right.endMs))
                consumed = 1
            }
        }
        output.addAll(owned.drop(consumed))
        previous = Previous(decoded, windowStartMs, windowEndMs, coreEndMs,
            if (owned.isEmpty()) null else output.lastOrNull())
    }

    fun finish(): List<TimedWord> = output.sortedBy { it.startMs }

    private fun overlaps(left: TimedWord, right: TimedWord) =
        maxOf(left.startMs, right.startMs) < minOf(left.endMs, right.endMs)
}
