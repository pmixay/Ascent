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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
    onToggleConversation: (String) -> Unit,
    onRename: (String, String, String) -> Unit,
    onExport: (String, Boolean) -> Unit,
) {
    var renameConversation by rememberSaveable { mutableStateOf<String?>(null) }
    var renameSpeaker by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var exportConversation by rememberSaveable { mutableStateOf<String?>(null) }
    val editable = !state.busy && !state.recording
    val timelineTurns = remember(state.conversations) {
        state.conversations.associate { it.id to TranscriptTimeline.turns(it.segments) }
    }

    Box(Modifier.fillMaxSize()) {
        WarmBackdrop(Modifier.fillMaxSize())
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            spacedItem("wordmark") { AscentWordmark(Modifier.widthIn(max = 620.dp).fillMaxWidth()) }
            spacedItem("capture") {
                GlassCard {
                    Text(if (state.recording) "СЛУШАЮ" else "НОВЫЙ РАЗГОВОР", modifier = Modifier.fillMaxWidth(),
                        fontSize = 11.sp, letterSpacing = 1.8.sp, textAlign = TextAlign.Center, color = Secondary)
                    Spacer(Modifier.height(10.dp))
                    Text(formatTime(state.elapsedMs), modifier = Modifier.fillMaxWidth(), fontSize = 42.sp,
                        fontWeight = FontWeight.Light, letterSpacing = (-1.2).sp, textAlign = TextAlign.Center,
                        color = Ink, style = TextStyle(fontFeatureSettings = "tnum"))
                    AudioWaveform(state.waveform, Modifier.fillMaxWidth().height(65.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        RecordOrb(recording = state.recording, level = state.level,
                            enabled = state.recording || (!state.busy && state.historyReady && state.modelDirectory != null), onClick = onRecord)
                    }
                    Text(if (state.recording) "Завершить разговор" else "Начать разговор",
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 15.sp, color = Ink)
                }
            }
            spacedItem("status") { ConversationStatus(state) }
            spacedItem("models") { ModelsStatus(state) }
            if (state.conversations.isEmpty()) {
                spacedItem("empty-history") {
                    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(vertical = 16.dp)) {
                        Text("Ваши разговоры", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Ink)
                        Spacer(Modifier.height(8.dp))
                        Text("Завершите разговор — здесь появятся участники, реплики и время.",
                            fontSize = 15.sp, lineHeight = 22.sp, color = Secondary)
                    }
                }
            } else {
                spacedItem("history-heading") {
                    Row(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Разговоры", modifier = Modifier.weight(1f), fontSize = 24.sp,
                            fontWeight = FontWeight.Medium, color = Ink)
                        Text("7 дней", fontSize = 13.sp, color = Secondary)
                    }
                }
                state.conversations.forEach { conversation ->
                    val expanded = conversation.id in state.expandedConversationIds
                    item(key = "${conversation.id}:header", contentType = "conversation-header") {
                        ConversationHeader(conversation, expanded) { onToggleConversation(conversation.id) }
                    }
                    if (expanded) {
                        val turns = timelineTurns.getValue(conversation.id)
                        itemsIndexed(turns,
                            key = { index, _ -> "${conversation.id}:turn:$index" },
                            contentType = { _, _ -> "conversation-turn" },
                        ) { index, turn ->
                            ConversationTimelineRow(conversation, turn, index, turns.lastIndex, editable) {
                                renameConversation = conversation.id
                                renameSpeaker = turn.speakerId
                                renameText = conversation.speakerName(turn.speakerId)
                            }
                        }
                        item(key = "${conversation.id}:footer", contentType = "conversation-footer") {
                            Column {
                                ConversationFooter(conversation, editable) { exportConversation = conversation.id }
                                Spacer(Modifier.height(18.dp))
                            }
                        }
                    } else item(key = "${conversation.id}:gap", contentType = "gap") { Spacer(Modifier.height(18.dp)) }
                }
            }
        }
    }
    if (renameConversation != null && renameSpeaker != null) AlertDialog(
        onDismissRequest = { renameConversation = null; renameSpeaker = null },
        title = { Text("Имя или роль") },
        text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it.take(80) },
            singleLine = true, label = { Text("Участник") }) },
        confirmButton = { TextButton(enabled = renameText.isNotBlank() && editable, onClick = {
            onRename(renameConversation!!, renameSpeaker!!, renameText)
            renameConversation = null
            renameSpeaker = null
        }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { renameConversation = null; renameSpeaker = null }) { Text("Отмена") } },
    )
    exportConversation?.let { id ->
        AlertDialog(onDismissRequest = { exportConversation = null }, title = { Text("Экспорт расшифровки") },
            text = { Column {
                TextButton(onClick = { exportConversation = null; onExport(id, false) }) { Text("Текст — TXT") }
                TextButton(onClick = { exportConversation = null; onExport(id, true) }) { Text("Участники и время — JSON") }
            } }, confirmButton = {},
            dismissButton = { TextButton(onClick = { exportConversation = null }) { Text("Отмена") } })
    }
}

@Composable
private fun ConversationStatus(state: ConversationState) {
    Row(Modifier.widthIn(max = 620.dp).fillMaxWidth().glass(20).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), color = Amber, strokeWidth = 2.dp)
        else Box(Modifier.size(8.dp).background(if (state.recording) Amber else Secondary, CircleShape))
        Text(state.status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            fontSize = 14.sp, lineHeight = 20.sp, color = Ink)
    }
}

@Composable
private fun ModelsStatus(state: ConversationState) {
    val ready = state.modelDirectory != null
    val availability = when {
        ready -> "Готовы"
        !state.modelsChecked -> "Проверяю готовность"
        state.modelPhase == ModelTransferPhase.ERROR -> "Подготовка приостановлена"
        state.modelPhase == ModelTransferPhase.WAITING -> "Ожидаю продолжения"
        else -> "Подготавливаю модели"
    }
    val retainedProgress = state.modelDownloadTotal > 0 && state.modelPhase in setOf(
        ModelTransferPhase.DOWNLOADING, ModelTransferPhase.VERIFYING,
        ModelTransferPhase.WAITING, ModelTransferPhase.ERROR,
    )
    val active = !state.modelsChecked || state.modelPhase in setOf(
        ModelTransferPhase.CHECKING, ModelTransferPhase.DOWNLOADING, ModelTransferPhase.VERIFYING,
    )
    val completed = state.modelDownloadBytes.coerceIn(0, state.modelDownloadTotal.coerceAtLeast(0))
    val fraction = if (retainedProgress) (completed.toDouble() / state.modelDownloadTotal).toFloat() else 0f
    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().testTag("model-status-panel")
        .glass(24).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).glass(14, emphasis = true), contentAlignment = Alignment.Center) {
                ModelStatusMark(ready, Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Модели", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
                Text(availability, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            }
        }
        if (!ready || state.modelPhase !in setOf(ModelTransferPhase.READY, ModelTransferPhase.IDLE)) {
            Text(state.modelStatus, fontSize = 14.sp, lineHeight = 21.sp, color = Ink)
        }
        if (retainedProgress || active) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                val progressModifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape)
                    .testTag("model-transfer-progress")
                if (retainedProgress) {
                    LinearProgressIndicator(progress = { fraction }, modifier = progressModifier,
                        color = Amber, trackColor = Amber.copy(alpha = 0.15f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${completed / 1_000_000} из ${(state.modelDownloadTotal + 999_999) / 1_000_000} МБ",
                            modifier = Modifier.weight(1f), fontSize = 14.sp, color = Ink,
                            style = TextStyle(fontFeatureSettings = "tnum"))
                        Text("${(fraction * 100).toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = Ink, style = TextStyle(fontFeatureSettings = "tnum"))
                    }
                } else LinearProgressIndicator(modifier = progressModifier,
                    color = Amber, trackColor = Amber.copy(alpha = 0.15f))
            }
        }
    }
}

@Composable
private fun ModelStatusMark(ready: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        if (ready) {
            drawLine(Ink, Offset(size.width * .18f, size.height * .51f),
                Offset(size.width * .42f, size.height * .74f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Ink, Offset(size.width * .42f, size.height * .74f),
                Offset(size.width * .84f, size.height * .28f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
        } else {
            val tile = size.width * .28f
            for (x in 0..1) for (y in 0..1) drawRoundRect(Ink,
                Offset(size.width * .14f + x * size.width * .44f, size.height * .14f + y * size.height * .44f),
                Size(tile, tile), CornerRadius(2.dp.toPx()))
        }
    }
}

/** Keep each speaker turn independently lazy while its phrases share one timeline element. */
private fun LazyListScope.spacedItem(key: String, content: @Composable () -> Unit) {
    item(key = key) {
        Column {
            content()
            Spacer(Modifier.height(18.dp))
        }
    }
}

private fun Modifier.conversationSurface(first: Boolean = false, last: Boolean = false): Modifier {
    val shape = RoundedCornerShape(
        topStart = if (first) 28.dp else 0.dp,
        topEnd = if (first) 28.dp else 0.dp,
        bottomStart = if (last) 28.dp else 0.dp,
        bottomEnd = if (last) 28.dp else 0.dp,
    )
    // A horizontal brush has identical colors at every row boundary, including lazy item seams.
    return widthIn(max = 620.dp).fillMaxWidth().clip(shape)
        .background(Brush.horizontalGradient(listOf(Color(0xFFFFE78B), Color(0xFFF9CF41), Color(0xFFFFE06D))))
}

@Composable
private fun ConversationHeader(conversation: SavedConversation, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.conversationSurface(first = true, last = !expanded).padding(20.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Развёрнут" else "Свёрнут" }
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(conversationDate(conversation.startedAtMs), fontSize = 18.sp,
                    fontWeight = FontWeight.Medium, color = Ink)
                Spacer(Modifier.height(5.dp))
                Text("${formatTime(conversation.durationMs)} — участников: ${conversation.participantCount}",
                    fontSize = 13.sp, color = Ink.copy(alpha = 0.78f))
            }
            Text(if (expanded) "−" else "+", modifier = Modifier.padding(start = 16.dp), fontSize = 28.sp, color = Ink)
        }
        if (!expanded) conversation.segments.firstOrNull()?.let {
            Text(it.text, modifier = Modifier.padding(top = 8.dp), maxLines = 2,
                overflow = TextOverflow.Ellipsis, color = Ink.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun ConversationTimelineRow(
    conversation: SavedConversation,
    turn: TimelineTurn,
    index: Int,
    lastIndex: Int,
    editable: Boolean,
    onRename: () -> Unit,
) {
    if (turn.pauseBeforeMs > 0) {
        Row(Modifier.conversationSurface().padding(horizontal = 20.dp).height(IntrinsicSize.Min)) {
            Canvas(Modifier.width(22.dp).fillMaxHeight()) {
                val x = 5.dp.toPx()
                drawLine(Ink.copy(alpha = 0.26f), Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
            }
            TimelinePause(turn.pauseBeforeMs, Modifier.weight(1f).padding(bottom = 14.dp))
        }
    }
    Row(Modifier.conversationSurface().testTag("conversation-turn-${conversation.id}-$index")
        .padding(horizontal = 20.dp).height(IntrinsicSize.Min)) {
        Canvas(Modifier.width(22.dp).fillMaxHeight()) {
            val x = 5.dp.toPx()
            val y = 13.dp.toPx()
            if (index > 0) drawLine(Ink.copy(alpha = 0.26f), Offset(x, 0f), Offset(x, y), 1.5.dp.toPx())
            if (index < lastIndex)
                drawLine(Ink.copy(alpha = 0.26f), Offset(x, y), Offset(x, size.height), 1.5.dp.toPx())
            drawCircle(Ink, 4.dp.toPx(), Offset(x, y))
        }
        Column(Modifier.weight(1f).padding(bottom = if (index < lastIndex) 20.dp else 0.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(conversation.speakerName(turn.speakerId), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = Ink, modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = editable && turn.speakerId != UNKNOWN_SPEAKER,
                            role = Role.Button, onClick = onRename)
                        .padding(vertical = 4.dp))
            }
            Text(formatTimelineRange(turn.startMs, turn.endMs), fontSize = 14.sp, color = Ink,
                style = TextStyle(fontFeatureSettings = "tnum"))
            Spacer(Modifier.height(3.dp))
            turn.phrases.forEach { phrase ->
                if (phrase.pauseBeforeMs > 0) {
                    TimelinePause(phrase.pauseBeforeMs, Modifier.padding(vertical = 12.dp))
                    Text(formatTimelineRange(phrase.startMs, phrase.endMs), fontSize = 14.sp, color = Ink,
                        style = TextStyle(fontFeatureSettings = "tnum"))
                    Spacer(Modifier.height(3.dp))
                }
                SelectionContainer { Text(phrase.text, color = Ink, fontSize = 17.sp, lineHeight = 25.sp) }
            }
        }
    }
}

@Composable
private fun TimelinePause(durationMs: Long, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 1.6.dp.toPx()
            val clockCenter = Offset(size.width / 2, size.height * 0.58f)
            drawCircle(Ink, size.width * 0.34f, clockCenter, style = Stroke(stroke))
            drawLine(Ink, Offset(size.width * 0.39f, stroke / 2), Offset(size.width * 0.61f, stroke / 2),
                strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(Ink, Offset(size.width / 2, stroke / 2), Offset(size.width / 2, size.height * 0.21f),
                strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(Ink, clockCenter, Offset(clockCenter.x, size.height * 0.38f),
                strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(Ink, clockCenter, Offset(size.width * 0.64f, size.height * 0.66f),
                strokeWidth = stroke, cap = StrokeCap.Round)
        }
        Text("Пауза ${formatTimelineSeconds(durationMs)} с", color = Ink, fontSize = 14.sp,
            style = TextStyle(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun ConversationFooter(conversation: SavedConversation, editable: Boolean, onExport: () -> Unit) {
    Column(Modifier.conversationSurface(last = true).padding(horizontal = 20.dp, vertical = 16.dp)) {
        if (conversation.segments.isEmpty()) Text("Речь не обнаружена", color = Ink, fontSize = 15.sp)
        else {
            Text("Время реплик и пауз приблизительное", color = Ink, fontSize = 14.sp, lineHeight = 20.sp)
            TextButton(onClick = onExport, enabled = editable, modifier = Modifier.align(Alignment.End)) {
                Text("Экспорт текста", color = Ink)
            }
        }
    }
}

private fun conversationDate(timestamp: Long): String = java.text.SimpleDateFormat(
    "d MMMM, HH:mm", java.util.Locale.forLanguageTag("ru"),
).format(java.util.Date(timestamp))

@Composable
private fun AscentWordmark(modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            append("Ascent")
            appendInlineContent("square-period", ".")
        },
        modifier = modifier,
        color = Ink,
        fontSize = 28.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-1.6).sp,
        inlineContent = mapOf("square-period" to InlineTextContent(
            Placeholder(0.24.em, 0.16.em, PlaceholderVerticalAlign.AboveBaseline),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Ink, topLeft = Offset(size.width - size.height, 0f),
                    size = Size(size.height, size.height))
            }
        }),
    )
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
        .semantics { contentDescription = if (recording) "Завершить разговор и расшифровать" else "Начать разговор" }) {
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
    AscentTheme { ConversationScreen(ConversationState(), {}, {}, { _, _, _ -> }, { _, _ -> }) }
}
