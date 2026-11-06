package com.pmixay.ascent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val HISTORY_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

/** Text only. Timestamps belong to capture and never change when a participant is renamed. */
data class SavedConversation(
    val id: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val segments: List<TranscriptSegment>,
    val speakerNames: Map<String, String>,
) {
    fun speakerName(id: String): String = speakerNames[id] ?: "Участник"
    val participantCount: Int get() = segments.map { it.speakerId }.distinct().count { it != UNKNOWN_SPEAKER }
}

/** Authenticated encryption with a fresh nonce on every write and atomic file replacement. */
internal class EncryptedJsonFile(
    private val file: File,
    private val keyProvider: () -> SecretKey = AndroidSecretKey::get,
) {
    fun read(): JSONObject? {
        if (!file.isFile) return null
        require(file.length() in 31..MAX_BYTES) { "Некорректный размер истории" }
        val encoded = file.readBytes()
        require(encoded[0] == VERSION) { "Неизвестный формат истории" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
        cipher.updateAAD(file.name.toByteArray(Charsets.UTF_8))
        return JSONObject(String(cipher.doFinal(encoded, 13, encoded.size - 13), Charsets.UTF_8))
    }

    fun write(value: JSONObject) {
        val plain = value.toString().toByteArray(Charsets.UTF_8)
        require(plain.size < MAX_BYTES - 32) { "История слишком велика" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(file.name.toByteArray(Charsets.UTF_8))
        require(cipher.iv.size == 12)
        val encrypted = cipher.doFinal(plain)
        plain.fill(0)
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val staging = File(file.parentFile, ".${file.name}-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(staging).use { stream ->
                stream.write(byteArrayOf(VERSION))
                stream.write(cipher.iv)
                stream.write(encrypted)
                stream.fd.sync()
            }
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { staging.delete() }
    }

    fun delete() { check(!file.exists() || file.delete()) { "Не удалось удалить историю" } }

    companion object {
        private const val VERSION: Byte = 1
        private const val MAX_BYTES = 32L * 1024 * 1024
    }
}

internal object AndroidSecretKey {
    @Synchronized fun get(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }
    private const val ALIAS = "ascent-local-history-v1"
}

internal class ConversationStore(
    root: File,
    keyProvider: () -> SecretKey = AndroidSecretKey::get,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val encrypted = EncryptedJsonFile(File(root, "conversations.enc"), keyProvider)
    private val legacyBase = File(root, "latest-transcript.json")
    private val legacy: File get() = legacyBase.takeIf { it.isFile } ?: File(legacyBase.path + ".bak")

    fun load(): List<SavedConversation> {
        val document = encrypted.read()
        if (document != null) require(document.getInt("schemaVersion") == 1)
        val stored = document?.getJSONArray("conversations")?.let { items ->
            List(items.length()) { decodeConversation(items.getJSONObject(it)) }
        }.orEmpty()
        if (!legacy.isFile) {
            val incomplete = File(legacyBase.path + ".new")
            check(!incomplete.exists() || incomplete.delete()) { "Не удалось удалить незавершённую расшифровку" }
        }
        val migrated = if (legacy.isFile) migrateLegacy() else null
        val all = (stored + listOfNotNull(migrated)).distinctBy { it.id }
        val retained = retained(all, now())
        if (all != retained || migrated != null) save(retained)
        if (migrated != null || legacy.isFile && legacy.length() == 0L || document != null) {
            listOf(legacyBase, File(legacyBase.path + ".bak"), File(legacyBase.path + ".new")).forEach {
                check(!it.exists() || it.delete()) { "Не удалось удалить старую расшифровку" }
            }
        }
        return retained
    }

    fun save(conversations: List<SavedConversation>): List<SavedConversation> {
        val retained = retained(conversations, now())
        encrypted.write(JSONObject().put("schemaVersion", 1).put("conversations", JSONArray().apply {
            retained.forEach { put(conversationJson(it)) }
        }))
        return retained
    }

    private fun migrateLegacy(): SavedConversation? {
        require(legacy.length() <= 10L * 1024 * 1024) { "Старая расшифровка слишком велика" }
        if (legacy.length() == 0L) return null
        val json = JSONObject(legacy.readText())
        val audioName = json.optString("audioFile")
        val capturedAt = audioName.removePrefix("conversation-").removeSuffix(".wav").toLongOrNull()
            ?: legacy.lastModified()
        return SavedConversation("legacy-$capturedAt", capturedAt, json.getJSONArray("segments").let { items ->
            (0 until items.length()).maxOfOrNull { items.getJSONObject(it).getLong("endMs") } ?: 0
        }, segments(json), names(json))
    }

    companion object {
        fun retained(conversations: List<SavedConversation>, nowMs: Long): List<SavedConversation> = conversations
            .filter { it.startedAtMs > nowMs - HISTORY_RETENTION_MS && it.startedAtMs <= nowMs }
            .map(::assignedParticipants)
            .sortedByDescending { it.startedAtMs }

        fun conversationJson(source: SavedConversation): JSONObject {
            val conversation = assignedParticipants(source)
            return JSONObject().apply {
                put("id", conversation.id)
                put("startedAtMs", conversation.startedAtMs)
                put("durationMs", conversation.durationMs)
                put("speakers", JSONArray().apply { conversation.speakerNames.forEach { (id, name) ->
                    if (id != UNKNOWN_SPEAKER) put(JSONObject().put("id", id).put("name", name))
                } })
                put("segments", JSONArray().apply { conversation.segments.forEach { segment ->
                    put(JSONObject().put("speakerId", segment.speakerId).put("speakerName", conversation.speakerName(segment.speakerId))
                        .put("speakerKnown", !segment.speakerInferred).put("speakerInferred", segment.speakerInferred)
                        .put("startMs", segment.startMs)
                        .put("endMs", segment.endMs).put("text", segment.text))
                } })
            }
        }

        private fun assignedParticipants(conversation: SavedConversation): SavedConversation {
            val segments = SpeakerAttribution.completeStored(conversation.segments)
            if (segments === conversation.segments) return conversation
            val ids = segments.map { it.speakerId }.distinct()
            val names = ids.mapIndexed { index, id ->
                id to (conversation.speakerNames[id] ?: "Участник ${index + 1}")
            }.toMap()
            return conversation.copy(segments = segments, speakerNames = names)
        }

        private fun decodeConversation(json: JSONObject): SavedConversation {
            val id = json.getString("id")
            val start = json.getLong("startedAtMs")
            val duration = json.getLong("durationMs")
            require(id.isNotBlank() && id.length <= 100 && start > 0 && duration >= 0)
            return SavedConversation(id, start, duration, segments(json), names(json))
        }
        private fun segments(json: JSONObject): List<TranscriptSegment> {
            val items = json.getJSONArray("segments")
            require(items.length() <= 100_000)
            return List(items.length()) { index -> items.getJSONObject(index).let {
                val start = it.getLong("startMs")
                val end = it.getLong("endMs")
                require(start >= 0 && end >= start)
                TranscriptSegment(it.getString("speakerId"), start, end, it.getString("text"),
                    it.optBoolean("speakerInferred", false))
            } }
        }
        private fun names(json: JSONObject): Map<String, String> {
            val items = json.getJSONArray("speakers")
            return buildMap { for (i in 0 until items.length()) items.getJSONObject(i).let {
                if (it.getString("id") != UNKNOWN_SPEAKER) put(it.getString("id"), it.getString("name").take(80))
            } }
        }
    }
}
