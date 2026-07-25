package dev.insua.jellycast.datastore

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.Server
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 复审的 Minor 项:[ServerStore.upsert] 用的是 `filterNot { it.id == server.id } + server`,
 * 于是**编辑一台已有服务器会把它挪到列表末尾**——用户在服务器选择器里改个名字、确认一次证书指纹,
 * 顺序就变了。列表顺序是用户的心理地图,不该因为一次编辑而抖动。
 *
 * 顺序逻辑抽成纯函数,离线可测(DataStore 那层需要 Android Context,不适合放 JVM 单测)。
 */
class ServerUpsertTest {

    private fun server(id: String, name: String = id) =
        Server(id = id, name = name, endpoints = listOf(Endpoint("http://host/$id", "局域网", 1)))

    @Test fun `更新已有服务器时就地替换,不改变顺序`() {
        val list = listOf(server("s1"), server("s2"), server("s3"))

        val result = upsertServer(list, server("s2", name = "改了名字"))

        assertEquals(listOf("s1", "s2", "s3"), result.map { it.id })
        assertEquals("改了名字", result[1].name)
    }

    @Test fun `新服务器追加到末尾`() {
        val list = listOf(server("s1"), server("s2"))

        val result = upsertServer(list, server("s3"))

        assertEquals(listOf("s1", "s2", "s3"), result.map { it.id })
    }

    @Test fun `空列表插入第一台`() {
        assertEquals(listOf("s1"), upsertServer(emptyList(), server("s1")).map { it.id })
    }

    @Test fun `更新第一台也不会被挪走`() {
        val list = listOf(server("s1"), server("s2"))

        val result = upsertServer(list, server("s1", name = "新名字"))

        assertEquals(listOf("s1", "s2"), result.map { it.id })
        assertEquals("新名字", result.first().name)
    }
}
