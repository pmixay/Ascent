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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ModelTransferState(
    val directory: File?, val message: String, val downloading: Boolean = false,
    val completedBytes: Long = 0, val totalBytes: Long = 0,
)

/** A lease protects the selected revision until both inference passes release it. */
internal class ModelLease(val directory: File, private val release: () -> Unit) : Closeable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

internal object ModelSelection {
    private val lock = Any()
    private val leases = mutableMapOf<String, Int>()
    fun root(context: Context) = File(context.filesDir, "models").apply { mkdirs() }
    fun current(context: Context): File? = synchronized(lock) {
        val path = context.getSharedPreferences("ascent", Context.MODE_PRIVATE).getString("modelPath", null)
            ?: return@synchronized null
        val file = File(path)
        if (file.parentFile?.canonicalFile != root(context).canonicalFile || !file.name.startsWith("gigaam-")) return@synchronized null
        try { ModelSpec.load(file); file } catch (_: Exception) { null }
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
        check(file.parentFile?.canonicalFile == root(context).canonicalFile && file.name.startsWith("gigaam-"))
        ModelSpec.load(file)
        check(context.getSharedPreferences("ascent", Context.MODE_PRIVATE).edit()
            .putString("modelPath", file.absolutePath).commit()) { "Не удалось сохранить выбор моделей" }
    }
    fun cleanup(context: Context) = synchronized(lock) {
        val selected = current(context)
        root(context).listFiles()?.filter {
            it.name.startsWith("gigaam-") && it != selected && (leases[it.absolutePath] ?: 0) == 0
        }?.forEach { it.deleteRecursively() }
    }
}

internal object ModelDownloads {
    private const val UNIQUE = "ascent-model-download"
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        enqueue(context)
        manager.enqueueUniquePeriodicWork("ascent-model-updates", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ModelUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints).build())
    }
    fun enqueue(context: Context) { enqueue(context, ExistingWorkPolicy.KEEP) }
    fun continueDownload(context: Context) = enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
    private fun enqueue(context: Context, policy: ExistingWorkPolicy) =
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, policy,
            OneTimeWorkRequestBuilder<ModelDownloadWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    fun observe(context: Context): Flow<ModelTransferState> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE).map { records ->
            val active = records.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: records.firstOrNull { !it.state.isFinished }
            val record = active ?: records.firstOrNull()
            val directory = ModelSelection.current(context)
            val progress = record?.progress
            val preferences = context.getSharedPreferences("ascent-model-transfer", Context.MODE_PRIVATE)
            val running = active?.state == WorkInfo.State.RUNNING
            val queued = active?.state == WorkInfo.State.ENQUEUED || active?.state == WorkInfo.State.BLOCKED
            val message = when {
                running -> progress?.getString("message") ?: if (directory == null) "Проверяю модели…" else "Проверяю обновления…"
                queued -> preferences.getString("waiting", null)
                    ?: if (directory == null) "Загрузка продолжится при доступной сети" else "Модели готовы. Проверка обновлений в очереди"
                directory != null -> "Модели готовы"
                else -> "Загрузка будет продолжена автоматически"
            }
            ModelTransferState(directory, message, running && progress?.getBoolean("transferring", false) == true,
                progress?.getLong("completed", 0) ?: 0, progress?.getLong("total", 0) ?: 0)
        }.flowOn(Dispatchers.IO)
}

/** Periodic checks share the same unique transfer queue; two downloads cannot race. */
class ModelUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ModelDownloads.enqueue(applicationContext)
        return Result.success()
    }
}

/** Short, resumable batches stay below the normal background-job execution limit. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val start = System.nanoTime()
        return try {
            applicationContext.getSharedPreferences("ascent-model-transfer", Context.MODE_PRIVATE).edit().remove("waiting").apply()
            runInterruptible(Dispatchers.IO) {
                ModelDownloader(ModelSelection.root(applicationContext),
                    currentDirectory = ModelSelection.current(applicationContext),
                    shouldYield = { System.nanoTime() - start > TimeUnit.MINUTES.toNanos(4) }).use { operation ->
                    val directory = operation.download { progress ->
                        setProgressAsync(workDataOf("completed" to progress.completedBytes, "total" to progress.totalBytes,
                            "transferring" to true,
                            "message" to if (progress.verifying) "Проверяю модели…" else "Загружаю модели в фоне…")).get()
                    }
                    // Selection and transaction commit form one non-suspending critical section.
                    ModelSelection.select(applicationContext, directory)
                    operation.commit()
                    ModelSelection.cleanup(applicationContext)
                }
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: DownloadYieldException) {
            // A completed batch is progress, not a failed attempt. Queue its successor
            // without growing the network-error backoff on every four-minute slice.
            withContext(Dispatchers.IO) { ModelDownloads.continueDownload(applicationContext).result.get() }
            Result.success()
        } catch (error: Exception) {
            // Verified prefixes persist. WorkManager retries after network loss, quota stops or reboot.
            val message = "Загрузка приостановлена: ${error.message ?: "нет соединения"}. Повторю автоматически."
            applicationContext.getSharedPreferences("ascent-model-transfer", Context.MODE_PRIVATE).edit()
                .putString("waiting", message.take(300)).apply()
            Result.retry()
        }
    }
}
