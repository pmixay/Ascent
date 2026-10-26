package com.pmixay.ascent

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ModelStatusPolicyTest {
    private val installed = File("/validated/model-revision")
    private val revision = "a".repeat(64)

    @Test fun currentVersionSurvivesFailedUpdatesWithoutAdvancingTheSuccessfulCheck() {
        for (phase in listOf(ModelTransferPhase.CHECKING, ModelTransferPhase.WAITING, ModelTransferPhase.ERROR)) {
            val state = resolveModelTransfer(installed, ModelTransferRecord(phase,
                message = "Не удалось проверить обновление", lastCheckedAtMs = 1_000_000,
                latestManifestSha256 = revision), active = phase == ModelTransferPhase.CHECKING,
                installedManifestSha256 = revision)
            assertEquals(ModelVersionStatus.CURRENT, state.versionStatus)
            assertEquals(1_000_000L, state.lastCheckedAtMs)
            assertEquals("Последняя версия", modelVersionLabel(state.versionStatus))
            assertEquals("Проверено 3 мин назад", modelCheckAgeLabel(state.lastCheckedAtMs, 1_180_000))
        }
    }

    @Test fun catalogRevisionDoesNotBecomeInstalledUntilSelectionMatches() {
        val checked = ModelTransferRecord(ModelTransferPhase.DOWNLOADING, lastCheckedAtMs = 1000,
            latestManifestSha256 = "b".repeat(64))
        val downloading = resolveModelTransfer(installed, checked, active = true, installedManifestSha256 = revision)
        assertEquals(ModelVersionStatus.UPDATE_AVAILABLE, downloading.versionStatus)
        assertEquals("Доступна новая версия", modelVersionLabel(downloading.versionStatus))
        val activated = resolveModelTransfer(installed, checked, active = true, installedManifestSha256 = "b".repeat(64))
        assertEquals(ModelVersionStatus.CURRENT, activated.versionStatus)
        assertEquals(1000L, activated.lastCheckedAtMs)
    }

    @Test fun installedModelsWithoutSuccessfulCheckNeverClaimToBeLatest() {
        val state = resolveModelTransfer(installed, ModelTransferRecord(ModelTransferPhase.READY),
            active = false, installedManifestSha256 = revision)
        assertEquals(ModelVersionStatus.UNCHECKED, state.versionStatus)
        assertEquals("Готовы к работе", modelVersionLabel(state.versionStatus))
        assertEquals("Проверка ещё не выполнена", modelCheckAgeLabel(state.lastCheckedAtMs, 1_000_000))
    }

    @Test fun missingFilesAndMalformedSavedChecksCannotClaimLatestVersion() {
        val record = ModelTransferRecord(ModelTransferPhase.READY, lastCheckedAtMs = 1000,
            latestManifestSha256 = revision)
        assertEquals(ModelVersionStatus.UNCHECKED,
            resolveModelTransfer(null, record, active = false, installedManifestSha256 = revision).versionStatus)
        for (invalid in listOf(record.copy(lastCheckedAtMs = -1), record.copy(latestManifestSha256 = "not-a-revision"))) {
            val state = resolveModelTransfer(installed, invalid, active = false, installedManifestSha256 = revision)
            assertEquals(ModelVersionStatus.UNCHECKED, state.versionStatus)
            assertEquals(0L, state.lastCheckedAtMs)
        }
    }

    @Test fun successfulCheckAgeHandlesMinuteHourDayBoundariesAndClockRollback() {
        assertEquals("Проверено только что", modelCheckAgeLabel(1000, 500))
        assertEquals("Проверено только что", modelCheckAgeLabel(1000, 60_999))
        assertEquals("Проверено 1 мин назад", modelCheckAgeLabel(1000, 61_000))
        assertEquals("Проверено 59 мин назад", modelCheckAgeLabel(1000, 3_600_999))
        assertEquals("Проверено 1 ч назад", modelCheckAgeLabel(1000, 3_601_000))
        assertEquals("Проверено 1 дн. назад", modelCheckAgeLabel(1000, 86_401_000))
    }

    @Test fun firstLaunchDoesNotClaimReadinessBeforeValidation() {
        val state = resolveModelTransfer(null, ModelTransferRecord(), active = false)
        assertNull(state.directory)
        assertEquals(ModelTransferPhase.IDLE, state.phase)
        assertFalse(state.downloading)
        assertEquals(0L, state.totalBytes)
    }

    @Test fun persistedSuccessCannotReplaceMissingOrInvalidFiles() {
        val state = resolveModelTransfer(null,
            ModelTransferRecord(ModelTransferPhase.READY, "Модели готовы", 100, 100), active = false)
        assertNull(state.directory)
        assertEquals(ModelTransferPhase.IDLE, state.phase)
        assertFalse(state.message.contains("готовы"))
        assertEquals(0L, state.completedBytes)
        assertEquals(0L, state.totalBytes)
    }

    @Test fun installedModelsAreReadyWithoutAnySavedTransferRecord() {
        val state = resolveModelTransfer(installed, ModelTransferRecord(), active = false)
        assertSame(installed, state.directory)
        assertEquals(ModelTransferPhase.READY, state.phase)
        assertEquals("Модели готовы", state.message)
    }

    @Test fun updateCheckDoesNotWithdrawInstalledModelsOrReuseOldProgress() {
        val state = resolveModelTransfer(installed,
            ModelTransferRecord(ModelTransferPhase.CHECKING, "Проверяю модели…", 100, 100), active = true)
        assertSame(installed, state.directory)
        assertEquals(ModelTransferPhase.CHECKING, state.phase)
        assertTrue(state.message.contains("обновления"))
        assertTrue(state.message.contains("готовы"))
        assertFalse(state.downloading)
        assertEquals(0L, state.totalBytes)
    }

    @Test fun offlineUpdateKeepsWorkingModelsAndPausedProgress() {
        val state = resolveModelTransfer(installed,
            ModelTransferRecord(ModelTransferPhase.WAITING, "Нет подключения", 40, 100), active = true)
        assertSame(installed, state.directory)
        assertTrue(state.message.contains("обновления"))
        assertTrue(state.message.contains("готовы"))
        assertEquals(ModelTransferPhase.WAITING, state.phase)
        assertFalse(state.downloading)
        assertEquals(40L, state.completedBytes)
        assertEquals(100L, state.totalBytes)
    }

    @Test fun oldTransferErrorDoesNotOverrideInstalledReadiness() {
        val state = resolveModelTransfer(installed,
            ModelTransferRecord(ModelTransferPhase.ERROR, "Модели не установлены /data/user/private/model.onnx"), active = false)
        assertSame(installed, state.directory)
        assertTrue(state.message.contains("готовы"))
        assertTrue(state.message.contains("Обновление"))
        assertFalse(state.message.contains("/data/"))
        assertFalse(state.message.contains("не установлены"))
    }

    @Test fun firstDownloadErrorDoesNotExposeRawInternalDetails() {
        val state = resolveModelTransfer(null,
            ModelTransferRecord(ModelTransferPhase.ERROR, "https://internal.invalid/path /data/private/test.bin"), active = false)
        assertNull(state.directory)
        assertEquals(ModelTransferPhase.ERROR, state.phase)
        assertFalse(state.message.contains("https://"))
        assertFalse(state.message.contains("/data/"))
        assertFalse(state.downloading)
    }

    @Test fun storageFailureKeepsAnActionableExplanation() {
        val missing = resolveModelTransfer(null,
            ModelTransferRecord(ModelTransferPhase.ERROR, "Недостаточно места. Освободите не менее 1000 МБ."), active = false)
        val updating = resolveModelTransfer(installed,
            ModelTransferRecord(ModelTransferPhase.ERROR, "ENOSPC /data/private"), active = false)
        assertTrue(missing.message.contains("Освободите"))
        assertTrue(updating.message.contains("обновления"))
        assertTrue(updating.message.contains("готовы"))
        assertSame(installed, updating.directory)
    }

    @Test fun downloadingAnUpdateKeepsUsableRevisionAndItsOwnProgress() {
        val state = resolveModelTransfer(installed,
            ModelTransferRecord(ModelTransferPhase.DOWNLOADING, completedBytes = 25, totalBytes = 100), active = true)
        assertSame(installed, state.directory)
        assertEquals(ModelTransferPhase.DOWNLOADING, state.phase)
        assertTrue(state.downloading)
        assertTrue(state.message.contains("обновление"))
        assertTrue(state.message.contains("готовы"))
        assertEquals(25L, state.completedBytes)
        assertEquals(100L, state.totalBytes)
    }

    @Test fun checkingDownloadedBytesDoesNotMakeFirstInstallReadyPrematurely() {
        val state = resolveModelTransfer(null,
            ModelTransferRecord(ModelTransferPhase.VERIFYING, completedBytes = 100, totalBytes = 100), active = true)
        assertNull(state.directory)
        assertEquals(ModelTransferPhase.VERIFYING, state.phase)
        assertTrue(state.downloading)
        assertEquals(100L, state.completedBytes)
        assertEquals(100L, state.totalBytes)
    }

    @Test fun interruptedOwnersDoNotLeaveAnIndefiniteActiveStatus() {
        for (phase in listOf(ModelTransferPhase.CHECKING, ModelTransferPhase.DOWNLOADING, ModelTransferPhase.VERIFYING)) {
            val state = resolveModelTransfer(null,
                ModelTransferRecord(phase, completedBytes = 40, totalBytes = 100), active = false)
            assertNull(state.directory)
            assertEquals(phase.toString(), ModelTransferPhase.WAITING, state.phase)
            assertFalse(state.downloading)
            assertEquals(if (phase == ModelTransferPhase.CHECKING) 0L else 40L, state.completedBytes)
        }
    }

    @Test fun completedDownloadLeftByAnOldProcessDoesNotObscureInstalledModels() {
        for (phase in listOf(ModelTransferPhase.DOWNLOADING, ModelTransferPhase.VERIFYING)) {
            val state = resolveModelTransfer(installed,
                ModelTransferRecord(phase, completedBytes = 100, totalBytes = 100), active = false)
            assertSame(installed, state.directory)
            assertEquals(ModelTransferPhase.READY, state.phase)
            assertFalse(state.downloading)
            assertEquals(0L, state.completedBytes)
            assertEquals(0L, state.totalBytes)
        }
    }

    @Test fun partialUpdateAfterRestartRemainsPausedWhileCurrentRevisionWorks() {
        val state = resolveModelTransfer(installed,
            ModelTransferRecord(ModelTransferPhase.DOWNLOADING, completedBytes = 30, totalBytes = 100), active = false)
        assertSame(installed, state.directory)
        assertEquals(ModelTransferPhase.WAITING, state.phase)
        assertFalse(state.downloading)
        assertEquals(30L, state.completedBytes)
        assertTrue(state.message.contains("готовы"))
    }

    @Test fun countersAreBoundedEvenWhenSavedDataIsStaleOrMalformed() {
        val excessive = resolveModelTransfer(null,
            ModelTransferRecord(ModelTransferPhase.DOWNLOADING, completedBytes = Long.MAX_VALUE, totalBytes = 100), active = true)
        assertEquals(100L, excessive.completedBytes)
        val negative = resolveModelTransfer(null,
            ModelTransferRecord(ModelTransferPhase.DOWNLOADING, completedBytes = -30, totalBytes = 100), active = true)
        assertEquals(0L, negative.completedBytes)
        val unknown = resolveModelTransfer(null,
            ModelTransferRecord(ModelTransferPhase.DOWNLOADING, completedBytes = 30, totalBytes = -1), active = true)
        assertEquals(0L, unknown.completedBytes)
        assertEquals(0L, unknown.totalBytes)
    }

    @Test fun activationMakesTheUiReadyWithoutWaitingForASecondTransferSuccess() {
        val verifying = ModelTransferRecord(ModelTransferPhase.VERIFYING, completedBytes = 100, totalBytes = 100)
        assertNull(resolveModelTransfer(null, verifying, active = true).directory)
        assertSame(installed, resolveModelTransfer(installed, verifying, active = true).directory)
        val finished = resolveModelTransfer(installed,
            ModelTransferRecord(ModelTransferPhase.READY, completedBytes = 100, totalBytes = 100), active = false)
        assertEquals(ModelTransferPhase.READY, finished.phase)
        assertEquals(0L, finished.totalBytes)
    }
}
