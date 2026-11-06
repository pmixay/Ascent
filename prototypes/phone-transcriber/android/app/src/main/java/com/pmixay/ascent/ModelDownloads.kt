package com.pmixay.ascent

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ModelTransferState(
    val directory: File?, val message: String, val downloading: Boolean = false,
    val completedBytes: Long = 0, val totalBytes: Long = 0,
    val phase: ModelTransferPhase = ModelTransferPhase.IDLE,
    val versionStatus: ModelVersionStatus = ModelVersionStatus.UNCHECKED,
    val lastCheckedAtMs: Long = 0,
)

/** A lease protects the selected revision until both inference passes release it. */
internal class ModelLease(val directory: File, private val release: () -> Unit) : Closeable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

internal object ModelSelection {
    private val lock = Any()
    private val leases = mutableMapOf<String, Int>()
    fun root(context: Context) = File(context.filesDir, "models").canonicalFile.apply { mkdirs() }
    fun current(context: Context): File? = synchronized(lock) {
        try {
            val path = context.getSharedPreferences("ascent", Context.MODE_PRIVATE).getString("modelPath", null)
                ?: return@synchronized null
            val file = File(path).canonicalFile
            if (file.parentFile != root(context) || !file.name.startsWith("gigaam-")) return@synchronized null
            ModelSpec.load(file)
            file
        } catch (_: Exception) { null }
    }
    fun acquire(context: Context): ModelLease = synchronized(lock) {
        val file = checkNotNull(current(context)) { "Модели ещё загружаются" }
        leases[file.absolutePath] = (leases[file.absolutePath] ?: 0) + 1
        ModelLease(file) { synchronized(lock) {
            val count = (leases[file.absolutePath] ?: 1) - 1
            if (count == 0) leases.remove(file.absolutePath) else leases[file.absolutePath] = count
            // Delete only this released revision; a worker may be promoting a new one.
            if (count == 0 && file != current(context)) file.deleteRecursively()
        } }
    }
    fun select(context: Context, file: File) = synchronized(lock) {
        val selected = file.canonicalFile
        check(selected.parentFile == root(context) && selected.name.startsWith("gigaam-"))
        ModelSpec.load(selected)
        check(context.getSharedPreferences("ascent", Context.MODE_PRIVATE).edit()
            .putString("modelPath", selected.absolutePath).commit()) { "Не удалось сохранить выбор моделей" }
    }
    fun cleanup(context: Context) = synchronized(lock) {
        val selected = current(context)
        root(context).listFiles()?.filter {
            it.name.startsWith("gigaam-") && it != selected && (leases[it.absolutePath] ?: 0) == 0
        }?.forEach { it.deleteRecursively() }
    }
}

/** Transfer hooks shared by the foreground owner and durable background recovery. */
internal data class ModelTransferControl(
    val source: ModelSource,
    val beforeRequest: () -> Unit,
    val retryRequest: (java.io.IOException, Int) -> Boolean,
    val shouldYield: () -> Boolean,
)

internal fun interface ModelTransferRunner {
    fun run(context: Context, control: ModelTransferControl, progress: (ModelDownloadProgress) -> Unit)
}

internal fun retryableModelFailure(error: Exception): Boolean = error is java.io.IOException &&
    error !is javax.net.ssl.SSLException && error !is java.io.FileNotFoundException &&
    (error !is ModelHttpException || error.statusCode in setOf(408, 425, 429) || error.statusCode in 500..599)

internal object ModelDownloads {
    private const val UNIQUE = "ascent-model-download"
    private const val PREFS = "ascent-model-transfer"
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val transferMutex = kotlinx.coroutines.sync.Mutex()
    private val transferActive = MutableStateFlow(false)
    private val selectionRefresh = MutableStateFlow(0L)

    /** Recheck local metadata after returning to the app or finishing a transfer. */
    fun refresh() { selectionRefresh.update { it + 1 } }

    // Internal injection seam: Android lifecycle tests can exercise the actual service
    // with an interruptible fake instead of downloading production model weights.
    @Volatile var transferRunner: ModelTransferRunner = ModelTransferRunner { context, control, progress ->
        ModelDownloader(ModelSelection.root(context), source = control.source,
            currentDirectory = ModelSelection.current(context), shouldYield = control.shouldYield,
            beforeRequest = control.beforeRequest, retryRequest = control.retryRequest,
            onCatalogChecked = { catalogChecked(context, it) }).use { operation ->
            val directory = operation.download(progress)
            ModelSelection.select(context, directory)
            operation.commit()
            ModelSelection.cleanup(context)
        }
    }

    /** Persisted fallback and daily checks do not attempt to start a service from the background. */
    fun schedule(context: Context) {
        enqueue(context)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("ascent-model-updates", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ModelUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints).build())
    }

    /** Called directly from a visible Activity; foreground ownership outlives its window. */
    fun startFromVisible(context: Context): Boolean {
        // REPLACE removes stale retry delays, and cancellation releases the shared transfer lock.
        recovery(context, replace = true, delaySeconds = 60)
        return try {
            val started = context.startForegroundService(android.content.Intent(context, ModelDownloadService::class.java)) != null
            if (!started) recovery(context, replace = true)
            started
        } catch (_: IllegalStateException) {
            recovery(context, replace = true)
            false
        } catch (_: SecurityException) {
            recovery(context, replace = true)
            false
        }
    }

    fun enqueue(context: Context) { request(context, ExistingWorkPolicy.KEEP) }
    fun recovery(context: Context, replace: Boolean = false, delaySeconds: Long = 0) =
        request(context, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, delaySeconds)
    fun continueDownload(context: Context, delaySeconds: Long = 0) =
        request(context, ExistingWorkPolicy.APPEND_OR_REPLACE, delaySeconds)
    private fun request(context: Context, policy: ExistingWorkPolicy, delaySeconds: Long = 0) =
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, policy,
            OneTimeWorkRequestBuilder<ModelDownloadWorker>().setConstraints(constraints)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build())

    fun cancelRecovery(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE) }

    internal suspend fun transfer(context: Context, control: ModelTransferControl,
        onStatus: (String) -> Unit = {}, progress: (ModelDownloadProgress) -> Unit) = transferMutex.withLock {
        fun status(message: String, phase: String) {
            publish(context, message, phase)
            onStatus(message)
        }
        transferActive.value = true
        refresh()
        try {
            // Queued/cancelled work cannot overwrite the current owner's status.
            status("Проверяю модели…", "check")
            kotlinx.coroutines.coroutineScope {
                val task = async(Dispatchers.IO) {
                    runInterruptible { transferRunner.run(context, control, progress) }
                }
                try { task.await() }
                finally { control.source.cancelActiveRequest() }
            }
            status("Модели готовы", "ready")
        } catch (error: Exception) {
            if (error is CancellationException || error is DownloadYieldException || retryableModelFailure(error)) {
                status("Загрузка продолжится автоматически", "waiting")
            } else status(error.message?.take(200) ?: "Не удалось подготовить модели", "error")
            throw error
        } finally {
            control.source.cancelActiveRequest()
            refresh()
            transferActive.value = false
        }
    }

    internal fun publish(context: Context, message: String, phase: String,
        completed: Long? = null, total: Long? = null) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("message", message).putString("phase", phase)
            .apply {
                if (phase in setOf("ready", "check", "idle")) {
                    putLong("completed", 0).putLong("total", 0)
                } else {
                    if (completed != null) putLong("completed", completed)
                    if (total != null) putLong("total", total)
                }
            }.apply()
    }

    internal fun catalogChecked(context: Context, catalog: ModelCatalog, checkedAtMs: Long = System.currentTimeMillis()) {
        require(checkedAtMs > 0 && catalog.manifestSha256.matches(Regex("[0-9a-f]{64}")))
        // Publish the revision and timestamp atomically, independently of transfer progress.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("latestManifestSha256", catalog.manifestSha256)
            .putLong("lastCheckedAtMs", checkedAtMs).apply()
    }

    private fun preferenceChanges(context: Context, name: String, key: String? = null): Flow<Unit> = callbackFlow {
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (key == null || changed == null || changed == key) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    private data class SelectedModel(val directory: File?, val manifestSha256: String?)

    private fun selection(context: Context): Flow<SelectedModel> = combine(
        preferenceChanges(context, "ascent", "modelPath"), selectionRefresh,
    ) { _, _ -> Unit }.conflate().map {
        val directory = ModelSelection.current(context)
        val revision = directory?.let { runCatching { File(it, "manifest.json").inputStream().use(::sha256) }.getOrNull() }
        SelectedModel(directory, revision)
    }
        .distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun persisted(context: Context): Flow<ModelTransferRecord> = preferenceChanges(context, PREFS).map {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // One snapshot avoids reading keys from different progress publications.
        val values = preferences.all
        val phase = when (values["phase"]) {
            "check" -> ModelTransferPhase.CHECKING
            "download" -> ModelTransferPhase.DOWNLOADING
            "verify" -> ModelTransferPhase.VERIFYING
            "waiting" -> ModelTransferPhase.WAITING
            "error" -> ModelTransferPhase.ERROR
            "ready" -> ModelTransferPhase.READY
            else -> ModelTransferPhase.IDLE
        }
        ModelTransferRecord(phase, values["message"] as? String ?: "",
            values["completed"] as? Long ?: 0, values["total"] as? Long ?: 0,
            values["lastCheckedAtMs"] as? Long ?: 0, values["latestManifestSha256"] as? String)
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun observe(context: Context): Flow<ModelTransferState> = combine(
        selection(context), persisted(context), transferActive,
    ) { selected, saved, active -> resolveModelTransfer(selected.directory, saved, active, selected.manifestSha256) }
        .distinctUntilChanged().flowOn(Dispatchers.IO)
}

class ModelUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ModelDownloads.enqueue(applicationContext)
        return Result.success()
    }
}

/** Recovery after reboot/process loss; an active foreground service remains the sole download owner. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (ModelDownloadService.running.value) {
            withContext(Dispatchers.IO) { ModelDownloads.continueDownload(applicationContext, 60).result.get() }
            return Result.success()
        }
        val start = System.nanoTime()
        val source = HttpsModelSource()
        return try {
            var lastProgress = 0L
            ModelDownloads.transfer(applicationContext, ModelTransferControl(source,
                beforeRequest = {}, retryRequest = { error, attempt ->
                    val delayMs = maxOf(minOf(1_000L shl (attempt - 1).coerceIn(0, 4), 10_000L),
                        (error as? ModelHttpException)?.retryAfterMs ?: 0)
                    if (System.nanoTime() - start + TimeUnit.MILLISECONDS.toNanos(delayMs) > TimeUnit.MINUTES.toNanos(4)) false
                    else { Thread.sleep(delayMs); true }
                }, shouldYield = { System.nanoTime() - start > TimeUnit.MINUTES.toNanos(4) })) { progress ->
                val now = System.nanoTime()
                if (now - lastProgress >= 1_000_000_000 || progress.verifying) {
                    ModelDownloads.publish(applicationContext,
                        if (progress.verifying) "Проверяю модели…" else "Загружаю модели в фоне…",
                        if (progress.verifying) "verify" else "download", progress.completedBytes, progress.totalBytes)
                    lastProgress = now
                }
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: DownloadYieldException) {
            withContext(Dispatchers.IO) { ModelDownloads.continueDownload(applicationContext).result.get() }
            Result.success()
        } catch (error: Exception) {
            if (retryableModelFailure(error)) {
                val serverDelay = (error as? ModelHttpException)?.retryAfterMs ?: 0
                if (serverDelay > 0) {
                    withContext(Dispatchers.IO) {
                        ModelDownloads.continueDownload(applicationContext, (serverDelay + 999) / 1000).result.get()
                    }
                    Result.success()
                } else Result.retry()
            } else {
                Result.failure()
            }
        } finally { source.cancelActiveRequest() }
    }
}
