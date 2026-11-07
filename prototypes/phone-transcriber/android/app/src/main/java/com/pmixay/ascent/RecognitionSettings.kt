package com.pmixay.ascent

import android.content.Context
import java.io.File

/**
 * The remembered language mode and the rules that decide whether a job can start with it.
 * The three modes and their measurements are in docs/interfaces/multilingual-recognizer.md.
 * Nothing here touches audio or transcript text.
 */
internal object RecognitionSettings {
    private const val PREFERENCES = "ascent"
    private const val KEY = "recognitionMode"

    /** Recommended default of the interface contract: Russian first, English switched on by the probe. */
    val DEFAULT = RecognitionMode.AUTO

    /**
     * Shown when a fixed multilingual choice has no installed files. It starts with "Модели" so the
     * job error is categorized as unavailable models. The user's choice is never replaced silently.
     */
    const val MULTILINGUAL_MISSING =
        "Модели для других языков не установлены. Выберите «Авто» или «Русский»."

    /** An unknown or missing stored value falls back to [DEFAULT], never to an arbitrary mode. */
    fun parse(stored: String?): RecognitionMode = RecognitionMode.entries.firstOrNull { it.name == stored } ?: DEFAULT

    /**
     * True when [mode] cannot run: a fixed multilingual choice without every pinned file present.
     * [RecognitionMode.AUTO] never blocks, because the engine then simply stays on GigaAM, and
     * [RecognitionMode.RUSSIAN_CIS] needs only the acoustic pack that every job already requires.
     */
    fun blocked(mode: RecognitionMode, multilingualDirectory: File?): Boolean =
        mode == RecognitionMode.MULTILINGUAL && !ParakeetFiles.installed(multilingualDirectory)

    fun load(context: Context): RecognitionMode =
        parse(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, mode: RecognitionMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
    }
}
