package com.pmixay.ascent

/**
 * Decision of the automatic mode from GigaAM's words at the start of a recording.
 *
 * GigaAM's character vocabulary has Latin and Cyrillic (plus Kazakh) letters. After each
 * recognized window the engine passes the words it owns; once [MIN_LETTERS] letters are seen,
 * or the probed cores reach [MAX_PROBE_MS], a Latin majority selects the multilingual mode and
 * anything else keeps Russian and CIS languages. A recording that ends undecided, and every tie,
 * stays in the Russian mode, the brief's first priority. Host check:
 * tools/language_probe_study.py, results in docs/interfaces/multilingual-recognizer.md.
 */
class LanguageProbe {
    private var latin = 0
    private var other = 0

    var decision: RecognitionMode? = null
        private set

    /** Adds one window's owned words; returns the decision once it is made, otherwise null. */
    fun observe(words: List<TimedWord>, probedUntilMs: Long): RecognitionMode? {
        decision?.let { return it }
        for (word in words) {
            for (char in word.text) {
                if (char in 'a'..'z') latin++ else if (char.isLetter()) other++
            }
        }
        if (latin + other >= MIN_LETTERS || probedUntilMs >= MAX_PROBE_MS) {
            decision = if (latin > other) RecognitionMode.MULTILINGUAL else RecognitionMode.RUSSIAN_CIS
        }
        return decision
    }

    companion object {
        const val MIN_LETTERS = 30
        const val MAX_PROBE_MS = 60_000L
    }
}
