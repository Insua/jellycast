package dev.insua.jellycast.model

data class SubtitleLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * 字幕时间轴,支持按播放位置定位当前行。
 *
 * 文件名叫"已排序",但缺陷 2(设计文档 §3.4)已证明这个假设不可靠:弹幕轨大量重叠
 * (3676 条里只有 53 条曾被原来假设"有序、不重叠"的二分查找命中),普通字幕在两句衔接处
 * 也偶尔重叠。[indexAt] 因此不再依赖有序/不重叠假设,改成线性扫描全部候选、取"覆盖当前
 * 位置且最晚开始"的一条 —— 这是唯一在两种输入(不重叠 / 重叠)下都给出合理结果的规则:
 * 不重叠时退化成"当前位置落在哪一行"(和原实现结果完全一致),重叠时选最新开始的那一条
 * 通常就是"最后一句还在说的话",符合歌词跟随的直觉。
 *
 * 用 O(n) 换正确性:字幕行数通常是几百到几千条,单次调用是微秒级;真正的调用频率也已经从
 * "固定轮询"改成按 [nextBoundaryAfter] 调度的按需触发(见 PlayerViewModel 缺陷 3 的修复),
 * 不再是性能敏感路径。
 */
class SubtitleTimeline(val lines: List<SubtitleLine>) {

    /**
     * 返回当前应高亮的行索引;无匹配返回 -1。
     *
     * 多条行覆盖同一位置时,取 [SubtitleLine.startMs] 最大(最晚开始)的一条;并列时取
     * 列表里更靠后的一条(任选一种确定性规则即可,这里选"后来居上")。
     */
    fun indexAt(positionMs: Long): Int {
        var bestIndex = -1
        var bestStart = Long.MIN_VALUE
        for (i in lines.indices) {
            val line = lines[i]
            if (positionMs in line.startMs..line.endMs && line.startMs >= bestStart) {
                bestStart = line.startMs
                bestIndex = i
            }
        }
        return bestIndex
    }

    /**
     * 缺陷 3(设计文档 §3.5)的调度基础:"下一个可能让 [indexAt] 结果发生变化的时间点"是多久
     * 之后 —— 即严格大于 [positionMs] 的最小行边界(某行的开始,或某行结束后的下一毫秒)。
     * 没有更多边界时返回 null(位置已经晚于所有行)。
     *
     * 调用方(`PlayerViewModel`)据此把轮询的 `delay` 精确设成"到下一次可能变化还有多久",
     * 而不是固定间隔碰运气——这样短于轮询间隔的字幕行也不会被跳过。
     */
    fun nextBoundaryAfter(positionMs: Long): Long? {
        var next: Long? = null
        for (line in lines) {
            if (line.startMs > positionMs && (next == null || line.startMs < next)) next = line.startMs
            val afterEnd = line.endMs + 1
            if (afterEnd > positionMs && (next == null || afterEnd < next)) next = afterEnd
        }
        return next
    }

    /**
     * 弹幕轨识别信号 2(设计文档 §3.3):条目密度异常。[SubtitleTrackRef.isLikelyDanmaku] 的标题
     * 关键字只是信号之一,覆盖不了命名花样百出的场景——日文コメント、通用 Track 1、纯数字标签的
     * 弹幕轨都不含 danmu/danmaku/弹幕这三个词,但它们的行密度远高于对白轨:实测样本 25 分钟集数,
     * 真字幕 774 条(≈31/分钟)、外挂弹幕 3676 条(≈147/分钟),相差近 5 倍。
     *
     * 用密度(条目数 / 分钟)而不是原始条目数——原始数字对短集数偏保守、对长片(90 分钟电影的
     * 对白行数本来就该比 20 分钟番剧多)偏激进,密度把片长这个变量除掉了。
     *
     * 这个信号只有在字幕文件解析完之后才算得出来(选轨发生在拉取字幕之前,行数此时未知)——
     * 调用方(`PlayerViewModel`)负责在解析后二次判定,判定为弹幕就降级并换下一个候选,而不是
     * 在这里直接丢弃数据(和 `indexAt`/`nextBoundaryAfter` 一样,这里只判定、不决策)。
     */
    fun isSuspiciouslyDense(runTimeMs: Long): Boolean {
        if (runTimeMs <= 0L || lines.isEmpty()) return false
        val minutes = runTimeMs / 60_000.0
        return (lines.size / minutes) > DANMAKU_DENSITY_PER_MINUTE_THRESHOLD
    }

    private companion object {
        /**
         * 60/分钟取在实测两个样本密度(≈31 与 ≈147)中间偏保守的一侧——约为真字幕密度的 2 倍、
         * 弹幕密度的四成,留了足够余量:语速快、行数多的正常对白轨(尤其是短集数)不会被误判,
         * 同时仍能稳定命中实测弹幕轨。
         */
        const val DANMAKU_DENSITY_PER_MINUTE_THRESHOLD = 60.0
    }
}
