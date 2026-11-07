package com.pmixay.ascent

import android.content.Context

/** Stage of the one active processing job. Model readiness is reported separately in the model panel. */
enum class ProcessingState {
    Idle, PreparingModels, ReadingInput, Recognizing, Diarizing, Formatting, Saving, Completed, Cancelled, Failed,
}

enum class JobErrorCategory {
    SourceUnreadable, FormatUnsupported, SourceMalformed, SourceTooLong, ModelsUnavailable, OutOfMemory, Storage, Interrupted, Internal,
}

/** [message] is the user-facing Russian explanation; it never contains transcript text. */
data class JobError(val category: JobErrorCategory, val message: String)

internal fun importFailureCategory(failure: ImportFailure): JobErrorCategory = when (failure) {
    ImportFailure.UNREADABLE, ImportFailure.NOT_SEEKABLE -> JobErrorCategory.SourceUnreadable
    ImportFailure.UNSUPPORTED -> JobErrorCategory.FormatUnsupported
    ImportFailure.MALFORMED, ImportFailure.EMPTY -> JobErrorCategory.SourceMalformed
    ImportFailure.TOO_LONG -> JobErrorCategory.SourceTooLong
}

/**
 * Admits one inference job at a time and recognizes late callbacks. A job publishes progress and its
 * final state only while it is the current job, so a finished or replaced job cannot overwrite a later one.
 */
internal class JobTracker {
    private var active: String? = null

    @Synchronized fun begin(id: String): Boolean {
        if (active != null) return false
        active = id
        return true
    }

    @Synchronized fun isCurrent(id: String): Boolean = active == id

    @Synchronized fun whileCurrent(id: String, block: () -> Unit) {
        if (active == id) block()
    }

    /** Runs [publish] and releases the slot as one step; false (and no publish) for a stale [id]. */
    @Synchronized fun finish(id: String, publish: () -> Unit): Boolean {
        if (active != id) return false
        publish()
        active = null
        return true
    }
}

/**
 * Maps the engine's progress messages to a stage and the fraction of the stage's media done. The
 * percentages are completed media units within one stage; a message without one is indeterminate.
 */
internal object EngineProgress {
    private val percent = Regex("(\\d{1,3})%")

    fun parse(message: String): Pair<ProcessingState, Float?>? {
        val fraction = percent.find(message)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)?.let { it / 100f }
        return when {
            message.startsWith("Загружаю") -> ProcessingState.PreparingModels to null
            message.startsWith("Распознаю") -> ProcessingState.Recognizing to fraction
            message.startsWith("Определяю") -> ProcessingState.Diarizing to fraction
            message.startsWith("Расшифровка готова") -> ProcessingState.Formatting to null
            else -> null
        }
    }
}

/**
 * Content-free note that a job was running. After a process death the next launch finds it and
 * says so; the app never claims to recover the audio, so the user selects the file again.
 */
internal object JobMarker {
    private const val KEY = "activeJob"
    @Volatile private var live: String? = null

    private fun preferences(context: Context) = context.getSharedPreferences("ascent", Context.MODE_PRIVATE)

    fun begin(context: Context, id: String) {
        live = id
        preferences(context).edit().putString(KEY, id).commit()
    }

    fun end(context: Context, id: String) {
        if (live == id) live = null
        val preferences = preferences(context)
        if (preferences.getString(KEY, null) == id) preferences.edit().remove(KEY).commit()
    }

    /** True when a previous process died mid-job; the marker is cleared so the notice shows once. */
    fun takeInterrupted(context: Context): Boolean {
        val preferences = preferences(context)
        val stored = preferences.getString(KEY, null)
        if (stored == null || stored == live) return false
        preferences.edit().remove(KEY).commit()
        return true
    }
}
