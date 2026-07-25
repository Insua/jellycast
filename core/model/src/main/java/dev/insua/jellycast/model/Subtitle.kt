package dev.insua.jellycast.model

data class SubtitleLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** 已排序的字幕时间轴,支持按播放位置二分查找当前行 */
class SubtitleTimeline(val lines: List<SubtitleLine>) {

    /** 返回当前应高亮的行索引;无匹配返回 -1 */
    fun indexAt(positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var candidate = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val line = lines[mid]
            when {
                positionMs < line.startMs -> hi = mid - 1
                positionMs > line.endMs -> { candidate = mid; lo = mid + 1 }
                else -> return mid
            }
        }
        // 落在两行之间时,不高亮任何行
        return if (candidate >= 0 && positionMs <= lines[candidate].endMs) candidate else -1
    }
}
