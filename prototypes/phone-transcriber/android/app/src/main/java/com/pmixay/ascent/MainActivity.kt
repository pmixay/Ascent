package com.pmixay.ascent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private lateinit var conversation: ConversationViewModel
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        conversation = ViewModelProvider(this)[ConversationViewModel::class.java]
        setContent {
            val state by conversation.state.collectAsStateWithLifecycle()
            var exportConversationId by rememberSaveable { mutableStateOf<String?>(null) }
            val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) conversation.startRecording() else conversation.microphoneDenied()
            }
            val exportText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                val id = exportConversationId
                exportConversationId = null
                if (uri != null && id != null) conversation.exportTranscript(id, uri, json = false)
            }
            val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                val id = exportConversationId
                exportConversationId = null
                if (uri != null && id != null) conversation.exportTranscript(id, uri, json = true)
            }
            DisposableEffect(state.recording, state.busy) {
                if (state.recording || state.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                    onToggleConversation = conversation::toggleConversation,
                    onRename = conversation::renameSpeaker,
                    onExport = { id, json ->
                        exportConversationId = id
                        val filename = "ascent-${System.currentTimeMillis()}"
                        if (json) exportJson.launch("$filename.json") else exportText.launch("$filename.txt")
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        conversation.setForeground(true)
        ModelDownloads.startFromVisible(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val preferences = getSharedPreferences("ascent", MODE_PRIVATE)
            if (!preferences.getBoolean("notificationPrompted", false)) {
                preferences.edit().putBoolean("notificationPrompted", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    override fun onStop() {
        if (!isChangingConfigurations) conversation.setForeground(false)
        super.onStop()
    }
}
