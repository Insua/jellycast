package dev.insua.jellycast.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「这台设备上次在听什么」—— 冷启动时把迷你播放条直接装填出来的数据来源(设计文档 §3)。
 *
 * ## 为什么不直接存 `MediaItem`
 *
 * `MediaItem` 是领域模型,字段会随功能演进增删(它已经在后续任务里被追加过 `unplayedItemCount`、
 * `isFavorite`、`seriesId` 等)。把它直接序列化进存储,等于让每一次模型改动都变成一次存储格式
 * 变更。这里只存两件事所需的最少字段:**恢复播放**(条目 id + 位置)与**渲染迷你条**
 * (标题、副标题、封面 tag、总时长)。
 *
 * ## 为什么损坏的内容要静默降级成 null
 *
 * 读这条记录发生在**冷启动路径**上。存储被写坏(进程在写入中途被杀、格式变更)时如果抛异常,
 * 用户看到的就是"打开就崩" —— 而这条记录只是一个便利功能,丢了最多是迷你条空着。
 */
@Serializable
data class LastPlayed(
    val itemId: String,
    val positionMs: Long,
    val title: String,
    val subtitle: String,
    val imageTag: String?,
    val runTimeMs: Long?,
    val updatedAt: Long,
)

private val KEY_LAST_PLAYED = stringPreferencesKey("last_played")

/**
 * 专用的 preferences 文件 —— **不得**和 [ServerStore] 的 `"servers"` 或 [PreferencesStore] 的
 * `"preferences"` 同名。DataStore 对同一份文件在进程内只允许存在一个活跃实例,撞名会在运行时抛
 * "There are multiple DataStores active for the same file" —— 而这类问题任何测试都发现不了,
 * 只能在真机/DI 装配时炸。见类 KDoc:构造函数刻意只接 [DataStore],不内部持有文件名,是为了
 * 让 [LastPlayedStoreTest] 能直接喂一个临时文件驱动的实例;这个 [Context] 版构造函数把文件名
 * 收拢回类内部,和 [ServerStore] / [PreferencesStore] 的既有约定(调用方只传 `Context`,
 * 不用知道文件名)保持一致,DI 装配处不会有机会手滑指到别的文件。
 */
private val Context.lastPlayedDataStore by preferencesDataStore("last_played")

class LastPlayedStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.lastPlayedDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    val lastPlayed: Flow<LastPlayed?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_PLAYED]?.let { raw ->
            runCatching { json.decodeFromString(LastPlayed.serializer(), raw) }.getOrNull()
        }
    }

    suspend fun save(record: LastPlayed) {
        dataStore.edit { it[KEY_LAST_PLAYED] = json.encodeToString(LastPlayed.serializer(), record) }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY_LAST_PLAYED) }
    }
}
