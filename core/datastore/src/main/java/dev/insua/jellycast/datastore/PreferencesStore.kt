package dev.insua.jellycast.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
private val KEY_DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")
private val KEY_CACHE_MAX_BYTES = longPreferencesKey("cache_max_bytes")

private const val DEFAULT_PLAYBACK_SPEED = 1.0f
private const val DEFAULT_REWIND_SECONDS = 15
private const val DEFAULT_FORWARD_SECONDS = 30
private const val DEFAULT_AUTO_PLAY_NEXT = true
private const val DEFAULT_LYRICS_ENABLED = true

/** design doc §5:诊断日志"默认开启,但用户可关闭"。 */
private const val DEFAULT_DIAGNOSTICS_ENABLED = true

/** Spike 实测建议默认值(见 docs/superpowers/specs/2026-07-25-spike-results.md):128kbps ≈ 58MB/小时。 */
private const val DEFAULT_AUDIO_BIT_RATE_KBPS = 128

/**
 * 设计文档 §4.3 / task-7-brief:缓存存储上限默认 1 GB(取 GiB),和 `CachePrefetchController`
 * 里 Task 7 落地前占位用的 `DEFAULT_MAX_BYTES` 是同一个值——Task 7 把那边的占位 lambda
 * 换成读这份偏好后,两处不能再各自维护一份、悄悄漂移。
 */
private const val DEFAULT_CACHE_MAX_BYTES = 1024L * 1024L * 1024L

/**
 * 「不限制」在 DataStore 里的哨兵值。这里不能像 [preferredSubtitleLanguage] 那样用"键不存在"
 * 表示 `null`——键不存在这个状态已经被"用户从没设置过,回退到默认 1 GB"占用了。如果也用它表示
 * 「不限制」,两种含义会被合并成同一个存储状态:用户主动选的「不限制」和"压根没碰过这项设置"
 * 变得无法区分,读回来只能落到默认的 1 GB——「不限制」就这样被悄悄改写成了「1 GB」,是 Task 7
 * brief 明确点名要防的坑。用一个不会和任何真实字节数选项(1/5/10 GB)相撞的负数表示「不限制」,
 * 读的时候再翻译回 `null`。
 */
private const val CACHE_MAX_BYTES_UNLIMITED_SENTINEL = -1L

/**
 * [PreferencesStore.cacheMaxBytes] 读写两侧的编解码,抽成纯函数——和 [upsertServer] 同一种取舍
 * (DataStore 本身需要 Android Context,不适合直接放 JVM 单测,见 [ServerStore] 的 KDoc)。
 *
 * `stored == null`(键不存在,用户从没碰过这项设置)落到 [DEFAULT_CACHE_MAX_BYTES];
 * `stored == `[CACHE_MAX_BYTES_UNLIMITED_SENTINEL] 翻译回 `null`(不限制);其余情况原样透传。
 */
internal fun decodeCacheMaxBytes(stored: Long?): Long? {
    val resolved = stored ?: DEFAULT_CACHE_MAX_BYTES
    return if (resolved == CACHE_MAX_BYTES_UNLIMITED_SENTINEL) null else resolved
}

/** `bytes == null`(不限制)编码成 [CACHE_MAX_BYTES_UNLIMITED_SENTINEL],其余原样透传。 */
internal fun encodeCacheMaxBytes(bytes: Long?): Long = bytes ?: CACHE_MAX_BYTES_UNLIMITED_SENTINEL

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

    /** design doc §5:诊断日志开关,默认开启。 */
    val diagnosticsEnabled: Flow<Boolean> = context.preferencesDataStore.data
        .map { it[KEY_DIAGNOSTICS_ENABLED] ?: DEFAULT_DIAGNOSTICS_ENABLED }

    /**
     * 设计文档 §4.3:音频缓存的存储上限,单位字节。`null` = 不限制——和 `planCache` 已经定好的
     * 契约一致(见 `CachePrefetchController` 类注释「maxBytes」),`CachePrefetchController` 的
     * `maxBytesProvider` 接的就是这个 Flow 的快照值。「不限制」只解除总量约束,窗口规则(只留
     * 当前及之后)与 10 集上限不受这项设置影响,照常在 `planCache` 里生效。
     */
    val cacheMaxBytes: Flow<Long?> = context.preferencesDataStore.data
        .map { decodeCacheMaxBytes(it[KEY_CACHE_MAX_BYTES]) }

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

    suspend fun setDiagnosticsEnabled(v: Boolean) {
        context.preferencesDataStore.edit { it[KEY_DIAGNOSTICS_ENABLED] = v }
    }

    suspend fun setCacheMaxBytes(v: Long?) {
        context.preferencesDataStore.edit { it[KEY_CACHE_MAX_BYTES] = encodeCacheMaxBytes(v) }
    }
}
