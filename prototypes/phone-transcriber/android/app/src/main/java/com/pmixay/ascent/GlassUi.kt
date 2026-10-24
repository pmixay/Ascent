package com.pmixay.ascent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.pow

private val Ink = Color(0xFF252216)
private val Secondary = Color(0xFF716951)
private val Amber = Color(0xFFE6AE14)
private val Cream = Color(0xFFFFF9DD)

@Composable
internal fun AscentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF956800), onPrimary = Color.White,
        background = Cream, surface = Color(0xFFFFFCF0), onSurface = Ink, onBackground = Ink), content = content)
}

@Composable
internal fun ConversationScreen(
    state: ConversationState,
    onRecord: () -> Unit,
    onOpenAudio: () -> Unit,
    onDownloadModels: () -> Unit,
    onCancelModelDownload: () -> Unit,
    onTranscribe: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: () -> Unit,
    onExport: (Boolean) -> Unit,
) {
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteDialog by rememberSaveable { mutableStateOf(false) }
    var exportDialog by rememberSaveable { mutableStateOf(false) }
    val editable = !state.busy && !state.recording

    Box(Modifier.fillMaxSize()) {
        WarmBackdrop(Modifier.fillMaxSize())
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ascent", modifier = Modifier.weight(1f), color = Ink, fontSize = 28.sp,
                        fontWeight = FontWeight.Medium, letterSpacing = (-1.6).sp)
                    Text("●  На устройстве", modifier = Modifier.glass(24).padding(horizontal = 14.dp, vertical = 9.dp),
                        color = Ink, fontSize = 11.sp)
                }
            }
            item {
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
                    Text("Разговор становится\nяснее.", fontSize = 35.sp, lineHeight = 38.sp,
                        fontWeight = FontWeight.Medium, letterSpacing = (-1.4).sp, color = Ink)
                    Spacer(Modifier.height(9.dp))
                    Text("Каждый голос. Каждая реплика.", fontSize = 15.sp, color = Secondary)
                }
            }
            if (state.modelDirectory == null || state.downloadingModels) {
                item { ModelDownloadCard(state, editable, onDownloadModels, onCancelModelDownload) }
            }
            item {
                GlassCard {
                    Text("ВАШ РАЗГОВОР", modifier = Modifier.fillMaxWidth(), fontSize = 10.sp,
                        letterSpacing = 1.8.sp, textAlign = TextAlign.Center, color = Secondary)
                    Spacer(Modifier.height(10.dp))
                    Text(formatTime(state.elapsedMs), modifier = Modifier.fillMaxWidth(), fontSize = 42.sp,
                        fontWeight = FontWeight.Light, letterSpacing = (-1.2).sp, textAlign = TextAlign.Center,
                        color = Ink, style = TextStyle(fontFeatureSettings = "tnum"))
                    AudioWaveform(state.waveform, Modifier.fillMaxWidth().height(65.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        RecordOrb(recording = state.recording, level = state.level, enabled = !state.busy, onClick = onRecord)
                    }
                    Text(if (state.recording) "Коснитесь, чтобы завершить" else if (state.downloadingModels) "Загружаю модели…"
                        else if (state.busy) "Обрабатываю на устройстве…" else "Коснитесь, чтобы начать",
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 14.sp, color = Ink)
                    Spacer(Modifier.height(7.dp))
                    Text("Аудио остаётся на вашем телефоне", modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center, fontSize = 11.sp, color = Secondary)
                }
            }
            item {
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    GlassAction("＋  Открыть аудиозапись", enabled = editable, onClick = onOpenAudio)
                    Spacer(Modifier.height(7.dp))
                    Text("WAV — 16 кГц — моно — до 30 минут", fontSize = 11.sp, color = Secondary)
                }
            }
            item {
                Row(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Amber, strokeWidth = 2.dp)
                    Text(state.status, fontSize = 13.sp, color = Secondary)
                }
            }
            item {
                GlassCard {
                    Text("ТЕКУЩАЯ ЗАПИСЬ", fontSize = 10.sp, letterSpacing = 1.5.sp, color = Secondary)
                    Spacer(Modifier.height(8.dp))
                    Text(if (state.audioFile == null) "Начните запись или откройте WAV" else "Разговор — ${formatTime(state.elapsedMs)}",
                        color = Ink, fontSize = 14.sp)
                    Spacer(Modifier.height(15.dp))
                    GlassAction("Расшифровать разговор", emphasis = true,
                        enabled = editable && state.audioFile != null && state.modelDirectory != null, onClick = onTranscribe)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassAction("Экспорт", modifier = Modifier.weight(1f), enabled = editable && state.segments.isNotEmpty()) { exportDialog = true }
                        GlassAction("Удалить", modifier = Modifier.weight(1f), enabled = editable && state.audioFile != null) { deleteDialog = true }
                    }
                }
            }
            if (state.segments.isEmpty()) {
                item {
                    GlassCard {
                        Text("Слова, в которых легко\nориентироваться.", fontSize = 22.sp, lineHeight = 27.sp,
                            fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp, color = Ink)
                        Spacer(Modifier.height(9.dp))
                        Text("После расшифровки здесь появятся участники, реплики и время разговора.", fontSize = 13.sp, color = Secondary)
                    }
                }
            } else {
                item {
                    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
                        Text("Расшифровка", fontSize = 25.sp, fontWeight = FontWeight.Medium, color = Ink)
                        Text("Коснитесь участника, чтобы указать имя или роль.", fontSize = 12.sp, color = Secondary)
                    }
                }
                itemsIndexed(state.segments, key = { index, segment -> "$index-${segment.startMs}" }) { _, segment ->
                    GlassCard {
                        if (segment.speakerId == UNKNOWN_SPEAKER) {
                            Text(UNKNOWN_SPEAKER_NAME, color = Secondary, fontSize = 14.sp)
                        } else {
                            GlassAction(state.speakerName(segment.speakerId), enabled = editable) {
                                renameId = segment.speakerId
                                renameText = state.speakerName(segment.speakerId)
                            }
                        }
                        Spacer(Modifier.height(9.dp))
                        Text("${formatTime(segment.startMs)} — ${formatTime(segment.endMs)}", fontSize = 11.sp, color = Secondary)
                        Spacer(Modifier.height(9.dp))
                        SelectionContainer { Text(segment.text, color = Ink, fontSize = 17.sp, lineHeight = 25.sp) }
                    }
                }
            }
            if (state.modelDirectory != null && !state.downloadingModels) {
                item { ModelDownloadCard(state, editable, onDownloadModels, onCancelModelDownload) }
            }
            item {
                Text("При сворачивании запись сохраняется. В приложении хранится одна текущая запись: следующая заменит её. Экспортируйте нужную расшифровку заранее.",
                    modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(), fontSize = 11.sp, color = Secondary)
            }
        }
    }

    if (renameId != null) AlertDialog(onDismissRequest = { renameId = null }, title = { Text("Имя или роль") },
        text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it.take(80) }, singleLine = true, label = { Text("Участник") }) },
        confirmButton = { TextButton(enabled = renameText.isNotBlank() && editable, onClick = {
            renameId?.let { onRename(it, renameText) }; renameId = null
        }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { renameId = null }) { Text("Отмена") } })

    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("Удалить запись?") },
        text = { Text("Текущая аудиозапись и её расшифровка будут удалены с устройства.") },
        confirmButton = { TextButton(enabled = editable, onClick = { deleteDialog = false; onDelete() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Отмена") } })

    if (exportDialog) AlertDialog(onDismissRequest = { exportDialog = false }, title = { Text("Экспорт расшифровки") },
        text = { Column {
            TextButton(onClick = { exportDialog = false; onExport(false) }) { Text("Текст — TXT") }
            TextButton(onClick = { exportDialog = false; onExport(true) }) { Text("Участники и временные метки — JSON") }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("Отмена") } })
}

@Composable
private fun ModelDownloadCard(state: ConversationState, editable: Boolean, onDownload: () -> Unit, onCancel: () -> Unit) {
    GlassCard {
        Text("Локальное распознавание", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
        Spacer(Modifier.height(7.dp))
        Text(if (state.modelDirectory != null && !state.downloadingModels) "Модели готовы. Интернет для обработки не нужен."
            else "Скачайте модели один раз — около 1 ГБ. Аудио и расшифровки не отправляются на сервер.", fontSize = 12.sp, color = Secondary)
        Spacer(Modifier.height(14.dp))
        if (state.downloadingModels) {
            if (state.modelDownloadTotal > 0) {
                LinearProgressIndicator(progress = {
                    (state.modelDownloadBytes.toDouble() / state.modelDownloadTotal).toFloat().coerceIn(0f, 1f)
                }, modifier = Modifier.fillMaxWidth(), color = Amber)
                Spacer(Modifier.height(8.dp))
                Text("${state.modelDownloadBytes / 1_000_000} из ${(state.modelDownloadTotal + 999_999) / 1_000_000} МБ",
                    color = Ink, fontSize = 13.sp)
            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Amber)
            Spacer(Modifier.height(8.dp))
            Text("Оставьте приложение открытым до завершения загрузки.", fontSize = 12.sp, color = Secondary)
            Spacer(Modifier.height(12.dp))
            GlassAction(if (state.cancellingModelDownload) "Останавливаю…" else "Отменить загрузку",
                enabled = !state.cancellingModelDownload, onClick = onCancel)
        } else GlassAction(if (state.modelDirectory == null) "Скачать модели" else "Переустановить модели",
            emphasis = state.modelDirectory == null, enabled = editable, onClick = onDownload)
    }
}

/** Translucent gradients and reflective borders, without claiming optical background blur. */
private fun Modifier.glass(radius: Int = 28, emphasis: Boolean = false): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    val colors = if (emphasis) listOf(Color(0xFFFFE78B), Color(0xFFF9CF41), Color(0xFFFFE06D))
        else listOf(Color.White.copy(alpha = 0.84f), Color(0xFFFFFFF9).copy(alpha = 0.48f), Color.White.copy(alpha = 0.68f))
    return clip(shape).background(Brush.linearGradient(colors)).border(1.dp,
        Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.8f))), shape)
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().glass().padding(23.dp), content = content)
}

@Composable
private fun GlassAction(text: String, modifier: Modifier = Modifier, emphasis: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.43f)) {
        Box(Modifier.glass(28, emphasis).padding(horizontal = 16.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun WarmBackdrop(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(Cream, Color(0xFFFFFCF1), Color(0xFFF7EFD0))))
        drawRect(Brush.radialGradient(listOf(Color(0xFFFFD647).copy(alpha = 0.57f), Color.Transparent),
            center = Offset(size.width * 0.95f, size.height * 0.3f), radius = size.width * 0.8f))
        drawRect(Brush.radialGradient(listOf(Color(0xFFFFE99A).copy(alpha = 0.45f), Color.Transparent),
            center = Offset(0f, size.height * 0.8f), radius = size.width * 0.85f))
    }
}

@Composable
private fun AudioWaveform(levels: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
        val step = size.width / levels.size
        levels.forEachIndexed { index, value ->
            val amplitude = value.coerceIn(0f, 1f).pow(0.55f) * size.height * 0.43f
            val x = (index + 0.5f) * step
            drawLine(if (value == 0f) Secondary.copy(alpha = 0.3f) else Amber,
                Offset(x, size.height / 2 - amplitude), Offset(x, size.height / 2 + amplitude + 1.dp.toPx()),
                strokeWidth = min(3.2.dp.toPx(), step * 0.5f), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun RecordOrb(recording: Boolean, level: Float, enabled: Boolean, onClick: () -> Unit) {
    Canvas(Modifier.size(124.dp).alpha(if (enabled) 1f else 0.43f).clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = if (recording) "Остановить запись и расшифровать" else "Начать запись разговора" }) {
        val radius = size.minDimension / 2 - 6.dp.toPx()
        val orbCenter = center
        drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color(0xFFFFD95F).copy(alpha = 0.5f), Color.Transparent),
            center = orbCenter, radius = radius + 5.dp.toPx()), radius + 5.dp.toPx())
        drawCircle(Brush.linearGradient(listOf(Color.White, Color(0xFFFFFADC).copy(alpha = 0.65f), Color.White)), radius)
        drawCircle(Brush.sweepGradient(listOf(Color(0xFFFFF0B2), Color(0xFFFFD8A6), Color(0xFFEEE2F5),
            Color.White, Color(0xFFFFDF70), Color(0xFFFFF0B2)), orbCenter), radius - 3.dp.toPx(),
            style = Stroke((4 + level.coerceIn(0f, 1f).pow(0.5f) * 3).dp.toPx()))
        drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFDC5C), Color(0xFFEFB518))), radius - 8.dp.toPx())
        if (recording) {
            val half = 10.dp.toPx()
            drawRoundRect(Ink, Offset(orbCenter.x - half, orbCenter.y - half), Size(half * 2, half * 2), CornerRadius(3.dp.toPx()))
        } else drawCircle(Ink, 11.dp.toPx())
    }
}

@Preview(name = "Ascent — yellow glass", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun ConversationPreview() {
    AscentTheme { ConversationScreen(ConversationState(), {}, {}, {}, {}, {}, { _, _ -> }, {}, {}) }
}
