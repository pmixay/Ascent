package com.pmixay.ascent

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A content-free marker that survives process death, not an audio/job recovery archive.
 * The random operation ID prevents an old job from clearing a newer import's marker.
 */
internal class PendingImportStore(root: File) {
    private val marker = File(root, "pending-import")
    private val staging = File(root, ".pending-import.tmp")

    @Synchronized
    fun begin(operationId: String) {
        require(operationId.isNotBlank() && operationId.length <= MAX_OPERATION_ID_LENGTH)
        check(marker.parentFile.isDirectory || marker.parentFile.mkdirs()) {
            "Не удалось подготовить состояние импорта"
        }
        try {
            FileOutputStream(staging).use { output ->
                output.write(operationId.toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            Files.move(staging.toPath(), marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staging.delete()
        }
    }

    @Synchronized
    fun complete(operationId: String) {
        if (!marker.isFile) return
        check(marker.length() in 1..MAX_OPERATION_ID_LENGTH.toLong()) {
            "Некорректное состояние импорта"
        }
        if (marker.readText(Charsets.US_ASCII) != operationId) return
        check(marker.delete()) { "Не удалось очистить состояние импорта" }
    }

    /** Returns whether an import was in flight; the interruption is reported only once. */
    @Synchronized
    fun consumeInterrupted(): Boolean {
        if (staging.exists()) check(staging.delete()) { "Не удалось очистить состояние импорта" }
        if (!marker.exists()) return false
        check(marker.isFile) { "Некорректное состояние импорта" }
        check(marker.delete()) { "Не удалось очистить состояние импорта" }
        return true
    }

    private companion object { const val MAX_OPERATION_ID_LENGTH = 100 }
}
