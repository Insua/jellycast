package dev.insua.jellycast.network.dto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * 覆盖 Task 3 review 发现的问题:三个 Sessions/Playing* 接口的请求体原为
 * `Map<String, Any>`,kotlinx.serialization 无法为 `Any` 生成序列化器,一旦接入
 * Retrofit 的 kotlinx.serialization converter 就会在运行时抛
 * SerializationException。这里直接用 Json 序列化每个 DTO,断言字段名与
 * docs/jellyfin-openapi.json 中 PlaybackStartInfo / PlaybackProgressInfo /
 * PlaybackStopInfo 的属性名逐字一致——既验证了"能序列化",也验证了"字段名没写错"
 * (Jellyfin 对未知字段静默忽略,大小写错了不会报错,只会让上报静默失效)。
 */
class SessionReportDtoTest {
    private val json = Json { encodeDefaults = true }

    @Test fun `PlaybackStartInfoDto 序列化字段名与 Jellyfin 规范一致`() {
        val dto = PlaybackStartInfoDto(
            itemId = "item-1",
            playSessionId = "sess-1",
            positionTicks = 12_345L,
            isPaused = true,
        )
        val obj = Json.parseToJsonElement(json.encodeToString(dto)).jsonObject

        assertEquals("item-1", obj["ItemId"]!!.jsonPrimitive.content)
        assertEquals("sess-1", obj["PlaySessionId"]!!.jsonPrimitive.content)
        assertEquals(12_345L, obj["PositionTicks"]!!.jsonPrimitive.long)
        assertEquals(true, obj["IsPaused"]!!.jsonPrimitive.boolean)
    }

    @Test fun `PlaybackProgressInfoDto 序列化字段名与 Jellyfin 规范一致`() {
        val dto = PlaybackProgressInfoDto(
            itemId = "item-2",
            playSessionId = "sess-2",
            positionTicks = 99_999L,
            isPaused = false,
        )
        val obj = Json.parseToJsonElement(json.encodeToString(dto)).jsonObject

        assertEquals("item-2", obj["ItemId"]!!.jsonPrimitive.content)
        assertEquals("sess-2", obj["PlaySessionId"]!!.jsonPrimitive.content)
        assertEquals(99_999L, obj["PositionTicks"]!!.jsonPrimitive.long)
        assertFalse(obj["IsPaused"]!!.jsonPrimitive.boolean)
    }

    @Test fun `PlaybackStopInfoDto 序列化字段名与 Jellyfin 规范一致`() {
        val dto = PlaybackStopInfoDto(
            itemId = "item-3",
            playSessionId = "sess-3",
            positionTicks = 42L,
        )
        val obj = Json.parseToJsonElement(json.encodeToString(dto)).jsonObject

        assertEquals("item-3", obj["ItemId"]!!.jsonPrimitive.content)
        assertEquals("sess-3", obj["PlaySessionId"]!!.jsonPrimitive.content)
        assertEquals(42L, obj["PositionTicks"]!!.jsonPrimitive.long)
        // PlaybackStopInfo 规范中没有 IsPaused 字段,确保我们没有多加。
        assertFalse(obj.containsKey("IsPaused"))
    }
}
