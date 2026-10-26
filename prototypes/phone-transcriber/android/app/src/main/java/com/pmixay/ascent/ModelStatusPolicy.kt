package com.pmixay.ascent

import java.io.File

/** Transfer activity is independent of whether an installed revision can perform inference. */
enum class ModelTransferPhase { IDLE, CHECKING, DOWNLOADING, VERIFYING, WAITING, ERROR, READY }
enum class ModelVersionStatus { UNCHECKED, CURRENT, UPDATE_AVAILABLE }

internal data class ModelTransferRecord(
    val phase: ModelTransferPhase = ModelTransferPhase.IDLE,
    val message: String = "",
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
    val lastCheckedAtMs: Long = 0,
    val latestManifestSha256: String? = null,
)

/** The caller supplies a validated selection; saved transfer success is never evidence of readiness. */
internal fun resolveModelTransfer(
    directory: File?,
    record: ModelTransferRecord,
    active: Boolean,
    installedManifestSha256: String? = null,
): ModelTransferState {
    val available = directory != null
    val total = record.totalBytes.coerceAtLeast(0)
    val completed = if (total > 0) record.completedBytes.coerceIn(0, total) else 0
    val checkedAt = record.lastCheckedAtMs.coerceAtLeast(0)
    val latest = record.latestManifestSha256?.takeIf { checkedAt > 0 && it.matches(Regex("[0-9a-f]{64}")) }
    val version = when {
        !available || latest == null || installedManifestSha256 == null -> ModelVersionStatus.UNCHECKED
        latest == installedManifestSha256 -> ModelVersionStatus.CURRENT
        else -> ModelVersionStatus.UPDATE_AVAILABLE
    }

    fun state(phase: ModelTransferPhase, message: String, progress: Boolean = false, running: Boolean = false) =
        ModelTransferState(directory = directory, message = message, downloading = running,
            completedBytes = if (progress) completed else 0, totalBytes = if (progress) total else 0,
            phase = phase, versionStatus = version, lastCheckedAtMs = if (latest != null) checkedAt else 0)

    fun ready() = state(ModelTransferPhase.READY, "Модели готовы")
    fun waiting(progress: Boolean = false): ModelTransferState {
        val network = record.message.contains("подключени", ignoreCase = true) ||
            record.message.contains("соединени", ignoreCase = true) || record.message.contains("связь", ignoreCase = true)
        val message = if (available) {
            if (network) "Ожидаю соединение для обновления. Установленные модели готовы к работе."
            else "Обновление продолжится автоматически. Установленные модели готовы к работе."
        } else {
            if (network) "Ожидаю соединение. Загрузка продолжится автоматически."
            else "Подготовка продолжится автоматически."
        }
        return state(ModelTransferPhase.WAITING, message, progress)
    }

    return when (record.phase) {
        ModelTransferPhase.IDLE -> if (available) ready()
            else if (active) state(ModelTransferPhase.CHECKING, "Проверяю модели…")
            else state(ModelTransferPhase.IDLE, "Подготовка начнётся автоматически")
        ModelTransferPhase.READY -> if (available) ready()
            else if (active) state(ModelTransferPhase.CHECKING, "Проверяю модели…")
            else state(ModelTransferPhase.IDLE, "Подготовка начнётся автоматически")
        ModelTransferPhase.CHECKING -> if (active) state(ModelTransferPhase.CHECKING,
            if (available) "Проверяю обновления. Установленные модели готовы к работе." else "Проверяю модели…")
            else waiting()
        ModelTransferPhase.DOWNLOADING, ModelTransferPhase.VERIFYING -> when {
            !active && available && total > 0 && completed == total -> ready()
            !active -> waiting(progress = true)
            record.phase == ModelTransferPhase.VERIFYING -> state(ModelTransferPhase.VERIFYING,
                if (available) "Проверяю обновление. Установленные модели готовы к работе." else "Проверяю загруженные модели…",
                progress = true, running = true)
            else -> state(ModelTransferPhase.DOWNLOADING,
                if (available) "Загружаю обновление. Установленные модели готовы к работе." else "Загружаю модели…",
                progress = true, running = true)
        }
        ModelTransferPhase.WAITING -> waiting(progress = true)
        ModelTransferPhase.ERROR -> {
            // Preserve an actionable storage reason, but never display raw exception paths or URLs.
            val storageFull = record.message.contains("Недостаточно места", ignoreCase = true) ||
                record.message.contains("ENOSPC", ignoreCase = true)
            state(ModelTransferPhase.ERROR, when {
                available && storageFull -> "Недостаточно места для обновления. Установленные модели готовы к работе."
                available -> "Обновление пока недоступно. Установленные модели готовы к работе."
                storageFull -> "Недостаточно места для моделей. Освободите память."
                else -> "Не удалось подготовить модели. Проверка повторится автоматически."
            }, progress = true)
        }
    }
}

/** A failed update attempt never changes the latest successful comparison or its age. */
internal fun modelVersionLabel(status: ModelVersionStatus): String = when (status) {
    ModelVersionStatus.CURRENT -> "Последняя версия"
    ModelVersionStatus.UPDATE_AVAILABLE -> "Доступна новая версия"
    ModelVersionStatus.UNCHECKED -> "Готовы к работе"
}

internal fun modelCheckAgeLabel(checkedAtMs: Long, nowMs: Long): String {
    if (checkedAtMs <= 0) return "Проверка ещё не выполнена"
    val minutes = (nowMs - checkedAtMs).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "Проверено только что"
        minutes < 60 -> "Проверено $minutes мин назад"
        minutes < 1440 -> "Проверено ${minutes / 60} ч назад"
        else -> "Проверено ${minutes / 1440} дн. назад"
    }
}
