package dev.insua.jellycast.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.Server
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [Server] / [Endpoint] 不是 @Serializable(它们是 :core:model 里的纯数据类,
 * 该模块不依赖 kotlinx.serialization)。这里用 surrogate 层做 JSON 编解码,
 * 并在 [toDomain] / [from] 之间做双向映射。
 */
@Serializable
data class ServerListSurrogate(val servers: List<ServerSurrogate>) {
    @Serializable
    data class ServerSurrogate(
        val id: String,
        val name: String,
        val endpoints: List<EndpointSurrogate>,
        val userId: String? = null,
        val accessToken: String? = null,
    )

    @Serializable
    data class EndpointSurrogate(
        val url: String,
        val label: String,
        val priority: Int,
        val trustedCertSha256: String? = null,
    )

    fun toDomain(): List<Server> = servers.map { s ->
        Server(
            id = s.id,
            name = s.name,
            endpoints = s.endpoints.map { Endpoint(it.url, it.label, it.priority, it.trustedCertSha256) },
            userId = s.userId,
            accessToken = s.accessToken,
        )
    }

    companion object {
        fun from(list: List<Server>) = ServerListSurrogate(
            list.map { s ->
                ServerSurrogate(
                    id = s.id,
                    name = s.name,
                    endpoints = s.endpoints.map { EndpointSurrogate(it.url, it.label, it.priority, it.trustedCertSha256) },
                    userId = s.userId,
                    accessToken = s.accessToken,
                )
            }
        )
    }
}

private val Context.serverDataStore by preferencesDataStore("servers")
private val KEY_SERVERS = stringPreferencesKey("servers_json")
private val KEY_ACTIVE = stringPreferencesKey("active_server_id")

/**
 * 服务器列表 + 当前激活服务器的持久化。
 * accessToken 明文落盘于 DataStore Preferences 文件——v1 已知取舍,见 Task 6 报告。
 */
class ServerStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val servers: Flow<List<Server>> = context.serverDataStore.data.map { prefs ->
        prefs[KEY_SERVERS]?.let {
            json.decodeFromString(ServerListSurrogate.serializer(), it).toDomain()
        } ?: emptyList()
    }

    val activeServerId: Flow<String?> = context.serverDataStore.data.map { it[KEY_ACTIVE] }

    suspend fun upsert(server: Server) = write { list ->
        list.filterNot { it.id == server.id } + server
    }

    suspend fun delete(id: String) = write { list -> list.filterNot { it.id == id } }

    suspend fun setActive(id: String) {
        context.serverDataStore.edit { it[KEY_ACTIVE] = id }
    }

    private suspend fun write(transform: (List<Server>) -> List<Server>) {
        context.serverDataStore.edit { prefs ->
            val current = prefs[KEY_SERVERS]
                ?.let { json.decodeFromString(ServerListSurrogate.serializer(), it).toDomain() }
                ?: emptyList()
            prefs[KEY_SERVERS] = json.encodeToString(
                ServerListSurrogate.serializer(), ServerListSurrogate.from(transform(current))
            )
        }
    }
}
