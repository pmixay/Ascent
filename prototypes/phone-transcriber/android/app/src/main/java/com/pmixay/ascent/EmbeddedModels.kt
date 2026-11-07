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
    private val state = MutableStateFlow(ModelTransferState(null, "Подготавливаю встроенные модели…", phase = ModelTransferPhase.VERIFYING))
    val states = state.asStateFlow()
    @Volatile private var selected: EmbeddedInstallation? = null
    @Volatile private var stamps: Map<String, Pair<Long, Long>> = emptyMap()
    private var preparing = false

    @Synchronized fun prepare(context: Context) {
        if (preparing || current(context) != null) return
        preparing = true
        state.value = ModelTransferState(null, "Подготавливаю встроенные модели…", phase = ModelTransferPhase.VERIFYING)
        val app = context.applicationContext
        scope.launch {
            try {
                ModelDownloads.installEmbedded(app) { completed, total ->
                    state.value = ModelTransferState(null, "Подготавливаю встроенные модели…", completedBytes = completed,
                        totalBytes = total, phase = ModelTransferPhase.VERIFYING)
                }.let { installed ->
                    selected = installed
                    stamps = snapshot(installed)
                    ModelSelection.cleanup(app)
                    state.value = ModelTransferState(installed.acoustic, "Встроенные модели готовы", phase = ModelTransferPhase.READY)
                }
            } catch (_: Exception) {
                selected = null
                stamps = emptyMap()
                state.value = ModelTransferState(null, "Не удалось подготовить встроенные модели. Проверьте свободное место и откройте приложение снова.", phase = ModelTransferPhase.ERROR)
            } finally { synchronized(this@EmbeddedModels) { preparing = false } }
        }
    }

    fun current(context: Context): File? {
        val installation = selected ?: return null
        if (installation.acoustic.canonicalFile.parentFile != ModelSelection.root(context)) return null
        if (snapshot(installation) != stamps) return null
        return try { ModelSpec.load(installation.acoustic); installation.acoustic } catch (_: Exception) { null }
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
        EmbeddedModelInstaller(root, bundle, { context.assets.open("models/$it") }).install(saved, progress)
    }
}
