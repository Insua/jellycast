package dev.insua.jellycast.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.preferencesDataStore by preferencesDataStore("preferences")

private val KEY_PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
private val KEY_REWIND_SECONDS = intPreferencesKey("rewind_seconds")
private val KEY_FORWARD_SECONDS = intPreferencesKey("forward_seconds")
private val KEY_AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
private val KEY_LYRICS_ENABLED = booleanPreferencesKey("lyrics_enabled")
private val KEY_PREFERRED_SUBTITLE_LANGUAGE = stringPreferencesKey("preferred_subtitle_language")
private val KEY_AUDIO_BIT_RATE_KBPS = intPreferencesKey("audio_bit_rate_kbps")

private const val DEFAULT_PLAYBACK_SPEED = 1.0f
private const val DEFAULT_REWIND_SECONDS = 15
private const val DEFAULT_FORWARD_SECONDS = 30
private const val DEFAULT_AUTO_PLAY_NEXT = true
private const val DEFAULT_LYRICS_ENABLED = true

/** Spike 实测建议默认值(见 docs/superpowers/specs/2026-07-25-spike-results.md):128kbps ≈ 58MB/小时。 */
private const val DEFAULT_AUDIO_BIT_RATE_KBPS = 128

/** 播放相关用户偏好持久化。 */
class PreferencesStore(private val context: Context) {

    val playbackSpeed: Flow<Float> = context.preferencesDataStore.data
        .map { it[KEY_PLAYBACK_SPEED] ?: DEFAULT_PLAYBACK_SPEED }

    val rewindSeconds: Flow<Int> = context.preferencesDataStore.data
        .map { it[KEY_REWIND_SECONDS] ?: DEFAULT_REWIND_SECONDS }

    val forwardSeconds: Flow<Int> = context.preferencesDataStore.data
        .map { it[KEY_FORWARD_SECONDS] ?: DEFAULT_FORWARD_SECONDS }

    val autoPlayNext: Flow<Boolean> = context.preferencesDataStore.data
        .map { it[KEY_AUTO_PLAY_NEXT] ?: DEFAULT_AUTO_PLAY_NEXT }

    val lyricsEnabled: Flow<Boolean> = context.preferencesDataStore.data
        .map { it[KEY_LYRICS_ENABLED] ?: DEFAULT_LYRICS_ENABLED }

    val preferredSubtitleLanguage: Flow<String?> = context.preferencesDataStore.data
        .map { it[KEY_PREFERRED_SUBTITLE_LANGUAGE] }

    /**
     * L1(`/Audio/{id}/universal`)转码目标码率,单位 kbps(修正 §3:64/128/256 三档)。
     * Spike 实测这是本产品"省流量"这个核心价值的唯一可调旋钮——移动网络受限时用户应该能自己调低。
     */
    val audioBitRateKbps: Flow<Int> = context.preferencesDataStore.data
        .map { it[KEY_AUDIO_BIT_RATE_KBPS] ?: DEFAULT_AUDIO_BIT_RATE_KBPS }

    suspend fun setPlaybackSpeed(v: Float) {
        context.preferencesDataStore.edit { it[KEY_PLAYBACK_SPEED] = v }
    }

    suspend fun setRewindSeconds(v: Int) {
        context.preferencesDataStore.edit { it[KEY_REWIND_SECONDS] = v }
    }

    suspend fun setForwardSeconds(v: Int) {
        context.preferencesDataStore.edit { it[KEY_FORWARD_SECONDS] = v }
    }

    suspend fun setAutoPlayNext(v: Boolean) {
        context.preferencesDataStore.edit { it[KEY_AUTO_PLAY_NEXT] = v }
    }

    suspend fun setLyricsEnabled(v: Boolean) {
        context.preferencesDataStore.edit { it[KEY_LYRICS_ENABLED] = v }
    }

    suspend fun setPreferredSubtitleLanguage(v: String?) {
        context.preferencesDataStore.edit {
            if (v == null) it.remove(KEY_PREFERRED_SUBTITLE_LANGUAGE) else it[KEY_PREFERRED_SUBTITLE_LANGUAGE] = v
        }
    }

    suspend fun setAudioBitRateKbps(v: Int) {
        context.preferencesDataStore.edit { it[KEY_AUDIO_BIT_RATE_KBPS] = v }
    }
}
