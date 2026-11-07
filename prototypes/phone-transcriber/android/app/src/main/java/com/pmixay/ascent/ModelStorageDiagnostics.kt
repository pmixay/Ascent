package com.pmixay.ascent

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Model-only byte counts. Never visits transcript/history/audio directories or reads file contents. */
internal fun modelDirectoryLogicalBytes(root: File): Long {
    check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "Model storage is unavailable" }
    return root.walkTopDown()
        .onEnter { !Files.isSymbolicLink(it.toPath()) }
        .onFail { _, error -> throw error }
        .filter { !Files.isSymbolicLink(it.toPath()) && it.isFile }
        .fold(0L) { bytes, file -> Math.addExact(bytes, file.length()) }
}

internal data class ModelStorageSample(val freeBytes: Long, val modelDirectoryBytes: Long) {
    init { require(freeBytes >= 0 && modelDirectoryBytes >= 0) }
}

internal data class ModelStorageObservation(
    val before: ModelStorageSample?,
    val after: ModelStorageSample?,
    val largestModelDirectoryBytes: Long?,
    val minimumFreeBytes: Long?,
    val samples: Int,
    val failedSamples: Int,
)

/** Samples are lower-bound observations, not an exact filesystem high-water mark. */
internal class ModelStorageProbe(
    private val sample: () -> ModelStorageSample,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var count = 0
    private var failed = 0
    private var largest: Long? = null
    private var minimumFree: Long? = null
    private var lastSampleNs = nanoTime()
    private val before = capture()

    private fun capture(): ModelStorageSample? = try {
        sample().also {
            count++
            largest = maxOf(largest ?: it.modelDirectoryBytes, it.modelDirectoryBytes)
            minimumFree = minOf(minimumFree ?: it.freeBytes, it.freeBytes)
        }
    } catch (_: Exception) {
        failed++
        null
    }

    fun progress() {
        val now = nanoTime()
        if (now - lastSampleNs >= 250_000_000L) {
            capture()
            lastSampleNs = now
        }
    }

    fun finish(): ModelStorageObservation {
        val after = capture()
        return ModelStorageObservation(before, after, largest, minimumFree, count, failed)
    }
}

/** One content-free record per successful preparation, collected off the readiness/inference path. */
internal object ModelStorageDiagnostics {
    const val TAG = "AscentModelStorage"
    private val latest = MutableStateFlow<String?>(null)
    val reports = latest.asStateFlow()

    private fun publish(json: String) {
        latest.value = json
        Log.i(TAG, json)
    }

    fun report(context: Context, payloadBytes: Long, observation: ModelStorageObservation, copiedBytes: Long, elapsedMs: Long) {
        // This block must never make verified models unavailable when an OEM refuses storage stats.
        try {
            val json = record(payloadBytes, observation, copiedBytes, elapsedMs)
                .put("source_commit", BuildConfig.SOURCE_COMMIT)
                .put("bundle_sha256", BuildConfig.EMBEDDED_BUNDLE_SHA256)
                .put("apk_abi", BuildConfig.NATIVE_ABI)
                .put("device_abis", Build.SUPPORTED_ABIS.joinToString(","))
                .put("android_api", Build.VERSION.SDK_INT)
                .put("storage_query_started_elapsed_ms", SystemClock.elapsedRealtime())
            val info = context.applicationInfo
            val apkFiles = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
            val apkBytes = apkFiles.map(::File).takeIf { files -> files.all { it.isFile && it.length() > 0 } }
                ?.fold(0L) { sum, file -> Math.addExact(sum, file.length()) }
            json.put("installed_apk_files_bytes", apkBytes ?: JSONObject.NULL)
            try {
                // Query only this package: Android requires no usage-access permission for this case.
                val stats = context.getSystemService(StorageStatsManager::class.java).queryStatsForPackage(
                    info.storageUuid ?: StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle(),
                )
                json.put("installed_app_bytes", stats.appBytes)
                    .put("installed_data_including_cache_bytes", stats.dataBytes)
                    .put("installed_cache_bytes", stats.cacheBytes)
                    .put("installed_stats_status", "measured")
            } catch (_: Exception) {
                json.put("installed_app_bytes", JSONObject.NULL)
                    .put("installed_data_including_cache_bytes", JSONObject.NULL)
                    .put("installed_cache_bytes", JSONObject.NULL)
                    .put("installed_stats_status", "unavailable")
            }
            publish(json.toString())
        } catch (_: Exception) {
            // No filenames, exception text, source metadata, user content or durable diagnostic file.
            publish("{\"schema_version\":1,\"event\":\"embedded_model_storage\",\"status\":\"unavailable\"}")
        }
    }

    internal fun record(payloadBytes: Long, observation: ModelStorageObservation, copiedBytes: Long, elapsedMs: Long): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("event", "embedded_model_storage")
        .put("expanded_payload_bytes", payloadBytes)
        .put("copied_asset_bytes", copiedBytes)
        .put("preparation_kind", if (copiedBytes == 0L) "verified-reuse" else "extracted")
        .put("extraction_and_validation_elapsed_ms", elapsedMs)
        .put("fresh_extraction_payload_and_reserve_bytes", Math.addExact(payloadBytes, EMBEDDED_MODEL_DISK_RESERVE_BYTES))
        .put("model_directory_logical_bytes_before", observation.before?.modelDirectoryBytes ?: JSONObject.NULL)
        .put("model_directory_logical_bytes_after_install", observation.after?.modelDirectoryBytes ?: JSONObject.NULL)
        .put("sampled_peak_model_directory_logical_bytes", observation.largestModelDirectoryBytes ?: JSONObject.NULL)
        .put("sampled_minimum_volume_free_bytes", observation.minimumFreeBytes ?: JSONObject.NULL)
        .put("volume_free_bytes_before", observation.before?.freeBytes ?: JSONObject.NULL)
        .put("volume_free_bytes_after_install", observation.after?.freeBytes ?: JSONObject.NULL)
        .put("successful_samples", observation.samples)
        .put("failed_samples", observation.failedSamples)
        .put("peak_scope", "sampled-model-logical-bytes-not-physical-allocation")
}
