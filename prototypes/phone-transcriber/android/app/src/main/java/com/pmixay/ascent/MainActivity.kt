package com.pmixay.ascent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private lateinit var conversation: ConversationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        conversation = ViewModelProvider(this)[ConversationViewModel::class.java]
        setContent {
            val state by conversation.state.collectAsStateWithLifecycle()
            val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) conversation.startRecording() else conversation.microphoneDenied()
            }
            val openAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let(conversation::importAudio)
            }
            val exportText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                uri?.let { conversation.exportTranscript(it, json = false) }
            }
            val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                uri?.let { conversation.exportTranscript(it, json = true) }
            }
            DisposableEffect(state.recording, state.downloadingModels) {
                if (state.recording || state.downloadingModels) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            AscentTheme {
                ConversationScreen(
                    state = state,
                    onRecord = {
                        if (state.recording) conversation.stopRecording()
                        else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) conversation.startRecording()
                        else microphone.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onOpenAudio = { openAudio.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave", "application/octet-stream")) },
                    onDownloadModels = conversation::downloadModels,
                    onCancelModelDownload = conversation::cancelModelDownload,
                    onTranscribe = conversation::transcribe,
                    onRename = conversation::renameSpeaker,
                    onDelete = conversation::deleteRecording,
                    onExport = { json ->
                        val filename = "ascent-${System.currentTimeMillis()}"
                        if (json) exportJson.launch("$filename.json") else exportText.launch("$filename.txt")
                    },
                )
            }
        }
    }

    override fun onStart() { super.onStart(); conversation.setForeground(true) }
    override fun onStop() {
        if (!isChangingConfigurations) conversation.setForeground(false)
        super.onStop()
    }
}
