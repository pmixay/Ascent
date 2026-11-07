package com.pmixay.ascent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PendingImportTest {
    private fun root() = Files.createTempDirectory("pending-import").toFile()

    @Test fun processDeathMarkerIsReportedOnce() {
        val root = root()
        try {
            PendingImportStore(root).begin("operation-1")
            // No complete() call simulates process death during source reads or native inference.
            val reopened = PendingImportStore(root)
            assertTrue(reopened.consumeInterrupted())
            assertFalse(reopened.consumeInterrupted())
        } finally { root.deleteRecursively() }
    }

    @Test fun completedImportDoesNotReportInterruption() {
        val root = root()
        try {
            val store = PendingImportStore(root)
            store.begin("operation-1")
            store.complete("operation-1")
            assertFalse(PendingImportStore(root).consumeInterrupted())
        } finally { root.deleteRecursively() }
    }

    @Test fun staleCompletionCannotClearANewerImport() {
        val root = root()
        try {
            val store = PendingImportStore(root)
            store.begin("operation-old")
            store.begin("operation-new")
            store.complete("operation-old")
            assertTrue(PendingImportStore(root).consumeInterrupted())
        } finally { root.deleteRecursively() }
    }

    @Test fun deathBeforeMarkerCommitDoesNotReportAnImportThatNeverStarted() {
        val root = root()
        try {
            val staging = File(root, ".pending-import.tmp").apply { writeText("uncommitted") }
            assertFalse(PendingImportStore(root).consumeInterrupted())
            assertFalse(staging.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun markerContainsOnlyTheRandomOperationId() {
        val root = root()
        try {
            PendingImportStore(root).begin("operation-1")
            assertEquals(listOf("pending-import"), root.list()!!.toList())
            assertEquals("operation-1", File(root, "pending-import").readText())
        } finally { root.deleteRecursively() }
    }
}
