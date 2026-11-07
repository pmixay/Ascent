package com.pmixay.ascent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecognitionSettingsTest {
    @Test fun everyStoredModeRoundTripsAndUnknownValuesFallBackToTheRecommendedDefault() {
        for (mode in RecognitionMode.entries) assertEquals(mode, RecognitionSettings.parse(mode.name))
        assertEquals(RecognitionMode.AUTO, RecognitionSettings.DEFAULT)
        assertEquals(RecognitionSettings.DEFAULT, RecognitionSettings.parse(null))
        assertEquals(RecognitionSettings.DEFAULT, RecognitionSettings.parse(""))
        // A value from a future or corrupted preference never selects an arbitrary recognizer.
        assertEquals(RecognitionSettings.DEFAULT, RecognitionSettings.parse("PARAKEET"))
        assertEquals(RecognitionSettings.DEFAULT, RecognitionSettings.parse("multilingual"))
    }

    @Test fun onlyAFixedMultilingualChoiceWithoutInstalledFilesIsBlocked() {
        val empty = Files.createTempDirectory("multilingual-empty").toFile()
        try {
            for (directory: File? in listOf(null, empty, File(empty, "missing"))) {
                // Nothing installs the files yet, so the fixed mode must refuse instead of switching models.
                assertTrue(RecognitionSettings.blocked(RecognitionMode.MULTILINGUAL, directory))
                // AUTO simply stays on GigaAM and the Russian mode never needs them.
                assertFalse(RecognitionSettings.blocked(RecognitionMode.AUTO, directory))
                assertFalse(RecognitionSettings.blocked(RecognitionMode.RUSSIAN_CIS, directory))
            }
        } finally { empty.deleteRecursively() }
    }

    @Test fun aFileWithTheWrongSizeDoesNotCountAsInstalled() {
        val directory = Files.createTempDirectory("multilingual-partial").toFile()
        try {
            // An interrupted extraction leaves a short file; ParakeetFiles checks the pinned size.
            for (asset in ParakeetFiles.ALL) File(directory, asset.name).writeBytes(ByteArray(8))
            assertTrue(RecognitionSettings.blocked(RecognitionMode.MULTILINGUAL, directory))
        } finally { directory.deleteRecursively() }
    }

    @Test fun theMissingModelMessageIsCategorizedAsUnavailableModels() {
        // describe() maps IllegalStateException messages that start with "Модели" to ModelsUnavailable.
        assertTrue(RecognitionSettings.MULTILINGUAL_MISSING.startsWith("Модели"))
    }
}
