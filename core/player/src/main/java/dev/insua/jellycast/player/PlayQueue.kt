package dev.insua.jellycast.player

import dev.insua.jellycast.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放队列(连播用):纯 Kotlin,不依赖真实播放器,可离线单测。
 * 到达队首/队尾时 [previous] / [next] 返回 null 且不移动游标,[current] 保持在边界项不变。
 */
class PlayQueue {
    private var items: List<MediaItem> = emptyList()
    private var index: Int = -1
    private val _current = MutableStateFlow<MediaItem?>(null)
    val current: StateFlow<MediaItem?> = _current.asStateFlow()

    fun setQueue(items: List<MediaItem>, startIndex: Int) {
        this.items = items
        this.index = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.lastIndex)
        _current.value = items.getOrNull(index)
    }

    fun hasNext(): Boolean = index >= 0 && index < items.lastIndex

    fun next(): MediaItem? {
        if (!hasNext()) return null
        index++
        _current.value = items[index]
        return _current.value
    }

    fun previous(): MediaItem? {
        if (index <= 0) return null
        index--
        _current.value = items[index]
        return _current.value
    }
}
