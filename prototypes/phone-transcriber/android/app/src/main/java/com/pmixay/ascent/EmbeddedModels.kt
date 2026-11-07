package com.pmixay.ascent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File

/** Process-owned bootstrap; no WorkManager, DNS or service start is required. */
internal object EmbeddedModels {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Existing model UI uses `downloading` as its active-preparation flag; no network transfer occurs here.
    private val state = MutableStateFlow(ModelTransferState(null, "Подготавливаю встроенные модели…", downloading = true, phase = ModelTransferPhase.VERIFYING))
    val states = state.asStateFlow()
    @Volatile private var selected: EmbeddedInstallation? = null
    @Volatile private var stamps: Map<String, Pair<Long, Long>> = emptyMap()
    private var preparing = false

    @Synchronized fun prepare(context: Context) {
        if (preparing || current(context) != null) return
        preparing = true
        state.value = ModelTransferState(null, "Подготавливаю встроенные модели…", downloading = true, phase = ModelTransferPhase.VERIFYING)
        val app = context.applicationContext
        scope.launch {
            var completedState: ModelTransferState? = null
            try {
                var lastProgress = 0L
                ModelDownloads.installEmbedded(app) { completed, total ->
                    val now = System.nanoTime()
                    if (now - lastProgress >= 250_000_000 || completed == total) {
                        state.value = ModelTransferState(null, "Подготавливаю встроенные модели…", downloading = true, completedBytes = completed,
                            totalBytes = total, phase = ModelTransferPhase.VERIFYING)
                        lastProgress = now
                    }
                }.let { installed ->
                    selected = installed
                    stamps = snapshot(installed)
                    ModelSelection.cleanup(app)
                    completedState = ModelTransferState(installed.acoustic, "Встроенные модели готовы", phase = ModelTransferPhase.READY)
                }
            } catch (error: Exception) {
                selected = null
                stamps = emptyMap()
                val message = when {
                    error.message?.contains("Недостаточно места") == true -> "Недостаточно места для встроенных моделей. Освободите память и откройте приложение снова."
                    error is IllegalArgumentException -> "Повреждён встроенный комплект моделей. Переустановите проверенный офлайн-APK."
                    else -> "Не удалось подготовить встроенные модели. Откройте приложение снова."
                }
                completedState = ModelTransferState(null, message, phase = ModelTransferPhase.ERROR)
            } finally {
                synchronized(this@EmbeddedModels) {
                    preparing = false
                    completedState?.let { state.value = it }
                }
            }
        }
    }

    fun current(context: Context): File? {
        val installation = selected ?: return null
        return try {
            if (installation.acoustic.canonicalFile.parentFile != ModelSelection.root(context)) null
            else if (snapshot(installation) != stamps) null
            else { ModelSpec.load(installation.acoustic); installation.acoustic }
        } catch (_: Exception) { null }
    }

    fun textDirectory(acoustic: File): File? = selected?.takeIf { it.acoustic == acoustic && snapshot(it) == stamps }?.text

    private fun snapshot(installation: EmbeddedInstallation): Map<String, Pair<Long, Long>> =
        listOf(installation.acoustic, installation.text).flatMap { directory ->
            directory.listFiles()?.map { it.absolutePath to (it.length() to it.lastModified()) }.orEmpty()
        }.toMap()

    suspend fun extract(context: Context, progress: (Long, Long) -> Unit): EmbeddedInstallation = runInterruptible {
        val bytes = context.assets.open("models/bundle.json").use { it.readBytes() }
        val bundle = EmbeddedBundle.parse(bytes, BuildConfig.EMBEDDED_BUNDLE_SHA256)
        val root = ModelSelection.root(context)
        val preferences = context.getSharedPreferences("ascent", Context.MODE_PRIVATE)
        val saved = preferences.getString("modelPath", null)?.let { acoustic ->
            preferences.getString("modelTextPath", null)?.let { EmbeddedInstallation(File(acoustic), File(it)) }
        }
        EmbeddedModelInstaller(root, bundle, { context.assets.open("models/$it") }, isLeased = ModelSelection::isLeased)
            .install(saved, progress)
    }
}
