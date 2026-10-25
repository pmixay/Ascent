package com.pmixay.ascent

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the real Android preference/Flow lifecycle without downloading or executing models. */
@RunWith(AndroidJUnit4::class)
class ModelStatusRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val selection get() = context.getSharedPreferences("ascent", Context.MODE_PRIVATE)
    private val transfer get() = context.getSharedPreferences("ascent-model-transfer", Context.MODE_PRIVATE)
    private val fixtures = mutableListOf<File>()
    private var previousPath: String? = null
    private var previousTransfer: Map<String, *> = emptyMap<String, Any>()
    private var previousComponentState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

    @Before fun isolateModelSelectionAndBackgroundOwners() {
        val component = ComponentName(context, ModelDownloadService::class.java)
        previousComponentState = context.packageManager.getComponentEnabledSetting(component)
        context.stopService(Intent(context, ModelDownloadService::class.java))
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (ModelDownloadService.running.value && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertFalse("An earlier transfer service did not stop", ModelDownloadService.running.value)
        context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        previousPath = selection.getString("modelPath", null)
        previousTransfer = transfer.all.toMap()
        assertTrue(selection.edit().remove("modelPath").commit())
        assertTrue(transfer.edit().clear().commit())
        ModelDownloads.refresh()
    }

    @After fun restorePreferencesAndRemoveMetadataFixtures() {
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        val edit = selection.edit()
        if (previousPath == null) edit.remove("modelPath") else edit.putString("modelPath", previousPath)
        assertTrue(edit.commit())
        val saved = transfer.edit().clear()
        previousTransfer.forEach { (key, value) -> restore(saved, key, value) }
        assertTrue(saved.commit())
        fixtures.forEach { it.deleteRecursively() }
        ModelDownloads.refresh()
        context.packageManager.setComponentEnabledSetting(ComponentName(context, ModelDownloadService::class.java),
            previousComponentState, PackageManager.DONT_KILL_APP)
    }

    @Test fun selectingVerifiedModelsUpdatesTheObserverWithoutTransferPublication() = observe { updates ->
        assertNull(updates.next { it.directory == null }.directory)
        val installed = fixture()
        ModelSelection.select(context, installed)
        val ready = updates.next { it.directory == installed }
        assertEquals(installed, ready.directory)
        ModelSelection.acquire(context).use { assertEquals(ready.directory, it.directory) }
        assertTrue("The test must not rely on a transfer event", transfer.all.isEmpty())
    }

    @Test fun existingModelsRemainUsableThroughFailedUpdateAndReconnectWait() = observe { updates ->
        updates.next { it.directory == null }
        val installed = fixture()
        ModelSelection.select(context, installed)
        updates.next { it.directory == installed }
        ModelDownloads.publish(context, "Не удалось проверить обновление", "error")
        val failedUpdate = updates.next { it.phase == ModelTransferPhase.ERROR }
        assertEquals(installed, failedUpdate.directory)
        ModelDownloads.publish(context, "Нет подключения", "waiting")
        val waiting = updates.next { it.phase == ModelTransferPhase.WAITING }
        assertEquals(installed, waiting.directory)
        assertFalse(waiting.downloading)
        ModelSelection.acquire(context).use { assertEquals(installed, it.directory) }
    }

    @Test fun restartingObservationDoesNotReviveAnOwnerlessSavedDownload() {
        val installed = fixture()
        ModelSelection.select(context, installed)
        ModelDownloads.publish(context, "Загружаю модели…", "download", 400, 1000)
        repeat(2) {
            observe { updates ->
                val state = updates.next { it.directory == installed }
                assertFalse("Saved progress is not a running transfer", state.downloading)
                assertEquals(installed, state.directory)
            }
        }
    }

    @Test fun clearingSelectionImmediatelyRemovesAvailability() = observe { updates ->
        updates.next { it.directory == null }
        val installed = fixture()
        ModelSelection.select(context, installed)
        updates.next { it.directory == installed }
        assertTrue(selection.edit().remove("modelPath").commit())
        assertNull(updates.next { it.directory == null }.directory)
    }

    @Test fun refreshRejectsMissingPayloadEvenWhenSavedTransferSaysReady() = observe { updates ->
        updates.next { it.directory == null }
        val installed = fixture()
        ModelSelection.select(context, installed)
        ModelDownloads.publish(context, "Модели готовы", "ready")
        updates.next { it.directory == installed && it.phase == ModelTransferPhase.READY }
        assertTrue(File(installed, "speaker.onnx").delete())
        ModelDownloads.refresh()
        val missing = updates.next { it.directory == null }
        assertNotEquals("A saved success cannot establish current file availability", ModelTransferPhase.READY, missing.phase)
    }

    @Test fun equivalentSelectedPathsDoNotDeleteTheActiveOrLeasedRevision() {
        val sandbox = File(context.cacheDir, "model-selection-${UUID.randomUUID()}").apply { mkdir() }
        val preferencePrefix = "model-selection-${UUID.randomUUID()}-"
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = sandbox
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(preferencePrefix + name, mode)
        }
        val preferences = isolated.getSharedPreferences("ascent", Context.MODE_PRIVATE)
        try {
            val first = fixture(isolated)
            // Simulate a legacy, equivalent path without relying on emulator-specific /data aliases.
            val alias = File(first.parentFile, "./${first.name}")
            assertTrue(preferences.edit().putString("modelPath", alias.absolutePath).commit())
            assertEquals(first.canonicalFile, ModelSelection.current(isolated))
            ModelSelection.cleanup(isolated)
            assertTrue("Cleanup deleted the selected revision through its path alias", first.isDirectory)
            val second = fixture(isolated)
            ModelSelection.acquire(isolated).use { lease ->
                ModelSelection.select(isolated, second)
                ModelSelection.cleanup(isolated)
                assertTrue("Cleanup deleted a revision still used by inference", first.isDirectory)
                assertEquals(first.canonicalFile, lease.directory)
                assertTrue(second.isDirectory)
            }
            assertFalse("A released old revision was not removed", first.exists())
            assertTrue(second.isDirectory)
        } finally {
            preferences.edit().clear().commit()
            sandbox.deleteRecursively()
        }
    }

    @Test fun cancellingAQueuedTransferCannotOverwriteTheActiveOwnersProgress() = observe { updates ->
        updates.next { it.directory == null }
        val installed = fixture()
        ModelSelection.select(context, installed)
        updates.next { it.directory == installed }
        val previousRunner = ModelDownloads.transferRunner
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val queuedPublications = AtomicInteger()
        val control = ModelTransferControl(ModelSource { error("This test must never open a network request") },
            beforeRequest = {}, retryRequest = { _, _ -> false }, shouldYield = { false })
        ModelDownloads.transferRunner = ModelTransferRunner { _, _, progress ->
            calls.incrementAndGet()
            progress(ModelDownloadProgress(400, 1000))
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "The test did not release its fake transfer" }
        }
        val owner = launch(Dispatchers.Default) {
            ModelDownloads.transfer(context, control) { progress ->
                ModelDownloads.publish(context, "Загружаю модели…", "download", progress.completedBytes, progress.totalBytes)
            }
        }
        var queued: kotlinx.coroutines.Job? = null
        try {
            assertTrue("The fake owner did not start", entered.await(10, TimeUnit.SECONDS))
            val active = updates.next { it.phase == ModelTransferPhase.DOWNLOADING && it.downloading }
            assertEquals(400L, active.completedBytes)
            val waitingTransfer = launch(start = CoroutineStart.UNDISPATCHED) {
                ModelDownloads.transfer(context, control, onStatus = { queuedPublications.incrementAndGet() }) { }
            }
            queued = waitingTransfer
            waitingTransfer.cancelAndJoin()
            assertEquals("A queued transfer entered another owner's critical section", 1, calls.get())
            assertEquals("A cancelled waiter published a status", 0, queuedPublications.get())
            assertEquals("download", transfer.getString("phase", null))
            assertEquals(400L, transfer.getLong("completed", 0))
            release.countDown()
            withTimeout(10_000) { owner.join() }
            val ready = updates.next { it.phase == ModelTransferPhase.READY }
            assertEquals(installed, ready.directory)
            assertFalse(ready.downloading)
            assertEquals(0L, ready.completedBytes)
            assertEquals(0L, ready.totalBytes)
        } finally {
            release.countDown()
            withContext(NonCancellable) {
                queued?.cancelAndJoin()
                owner.cancelAndJoin()
            }
            ModelDownloads.transferRunner = previousRunner
        }
    }

    private fun observe(action: suspend CoroutineScope.(Channel<ModelTransferState>) -> Unit) = runBlocking {
        val updates = Channel<ModelTransferState>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { ModelDownloads.observe(context).collect { updates.send(it) } }
        try { action(this, updates) }
        finally { collector.cancelAndJoin(); updates.close() }
    }

    private suspend fun Channel<ModelTransferState>.next(predicate: (ModelTransferState) -> Boolean): ModelTransferState =
        withTimeout(10_000) {
            var result = receive()
            while (!predicate(result)) result = receive()
            result
        }

    /** Valid metadata and tiny payloads exercise selection only, never ONNX inference. */
    private fun fixture(target: Context = context): File {
        val directory = File(ModelSelection.root(target), "gigaam-status-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        fixtures += directory
        val payloads = linkedMapOf(
            "asr.onnx" to byteArrayOf(1, 2, 3),
            "speaker.onnx" to byteArrayOf(4, 5, 6),
            "tokens.json" to JSONArray(listOf("а", " ")).toString().toByteArray(),
            "LICENSE-asr.txt" to "ASR test license".toByteArray(),
            "LICENSE-speaker.txt" to "Speaker test license".toByteArray(),
            "smoke-report.json" to "{}".toByteArray(),
        )
        val entries = JSONObject()
        payloads.forEach { (name, bytes) ->
            File(directory, name).writeBytes(bytes)
            entries.put(name, JSONObject().put("bytes", bytes.size).put("sha256", bytes.inputStream().use(::sha256)))
        }
        val manifest = JSONObject().put("schema_version", 1).put("model_id", ModelSpec.MODEL_ID)
            .put("sample_rate", 16000).put("channels", 1).put("min_samples", 4000).put("max_samples", 400000)
            .put("asr_file", "asr.onnx").put("tokens_file", "tokens.json").put("speaker_file", "speaker.onnx")
            .put("speaker_model_id", "3dspeaker-eres2netv2-2024").put("speaker_runtime", "sherpa-onnx")
            .put("blank_id", 2).put("vocabulary_size", 2).put("files", entries)
        File(directory, "manifest.json").writeText(manifest.toString())
        ModelSpec.load(directory, verifyHashes = true)
        return directory
    }

    private fun restore(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
}
