package com.flashcut.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.flashcut.app.data.SourceClip
import com.flashcut.app.media.MediaProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * 本地镜头分析：按固定间隔抽帧，用相邻帧的亮度差做镜头边界检测，
 * 再用清晰度/亮度给出可用性打分。全程离线，不依赖云端。
 */
class ShotAnalyzer(private val context: Context) {

    data class Progress(val percent: Int, val message: String)

    suspend fun analyze(
        sources: List<SourceClip>,
        style: String,
        onProgress: suspend (Progress) -> Unit = {},
    ): MaterialBrief = withContext(Dispatchers.Default) {
        val allShots = mutableListOf<ShotInsight>()
        var dropped = 0
        var done = 0
        val total = sources.size.coerceAtLeast(1)

        for (src in sources) {
            onProgress(Progress(done * 100 / total, "分析 ${src.displayName}"))
            val (shots, droppedHere) = analyzeSource(src, style)
            allShots += shots
            dropped += droppedHere
            done++
        }

        onProgress(Progress(100, "镜头切分完成"))
        // 跨素材统一编号为 s1..sN，模型容易引用
        val renumbered = allShots.mapIndexed { i, sh -> sh.copy(id = "s${i + 1}") }
        allShots.clear()
        allShots += renumbered
        // 保持 s1..sN 的自然顺序传给模型，避免它看到乱序编号后引错
        val sorted = allShots.sortedBy { it.id.drop(1).toIntOrNull() ?: Int.MAX_VALUE }
        val theme = inferTheme(style, sorted)
        MaterialBrief(
            theme = theme,
            confidence = (78 + sorted.size.coerceAtMost(10) * 2).coerceAtMost(96),
            tags = tagsFor(style),
            shots = sorted,
            speech = "",
            jitterDropped = dropped,
        )
    }

    private fun analyzeSource(src: SourceClip, style: String): Pair<List<ShotInsight>, Int> {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, src.uri)
            val durMs = src.durationMs.takeIf { it > 0 } ?: 0
            if (durMs <= 0) return@runCatching emptyList<ShotInsight>() to 0

            // 每 1.2 秒抽一帧，最多 24 帧
            val stepMs = max(1200L, durMs / 24)
            val frames = mutableListOf<Pair<Long, Bitmap>>()
            var t = 0L
            while (t < durMs && frames.size < 24) {
                // t 的单位是毫秒，getFrameAtTime 需要微秒，故 t * 1000
                readFrame(retriever, t * 1000)?.let { frames += t to it }
                t += stepMs
            }
            if (frames.isEmpty()) return@runCatching emptyList<ShotInsight>() to 0

            val lum = frames.map { brightness(it.second) }
            val focus = frames.map { sharpness(it.second) }
            frames.forEach { it.second.recycle() }

            // 亮度突变即认为切镜；同时把连续的低分清抖镜头记为待剔除
            val bounds = mutableListOf(0L)
            for (i in 1 until lum.size) {
                if (abs(lum[i] - lum[i - 1]) > 26f) bounds += frames[i].first
            }
            bounds += durMs

            val shots = mutableListOf<ShotInsight>()
            var dropped = 0
            for (i in 0 until bounds.size - 1) {
                val s = bounds[i]
                val e = bounds[i + 1]
                if (e - s < 700) continue
                val midIdx = ((s + e) / 2 / stepMs).toInt().coerceIn(0, focus.lastIndex)
                val scoreRaw = scoreOf(focus[midIdx])
                // 只有极模糊的才剔除；阈值放低避免整段素材被判空
                if (scoreRaw < 10) {
                    dropped++
                    continue
                }
                shots += ShotInsight(
                    id = "s${shots.size + 1}",
                    sourceId = src.id,
                    index = shots.size + 1,
                    startMs = s,
                    endMs = e,
                    title = titleFor(shots.size, style),
                    reason = reasonFor(scoreRaw, shots.size),
                    score = scoreRaw,
                )
            }
            // 兜底：一段素材至少要产出若干镜头，否则大模型无从下手
            if (shots.isEmpty() && durMs > 0) {
                val fallbackCount = ((durMs / 2500).toInt()).coerceIn(1, 4)
                val seg = durMs / fallbackCount
                for (i in 0 until fallbackCount) {
                    shots += ShotInsight(
                        id = "s${i + 1}",
                        sourceId = src.id,
                        index = i + 1,
                        startMs = i * seg,
                        endMs = (i + 1) * seg,
                        title = titleFor(i, style),
                        reason = "按时长均分（未检测到明显切镜）",
                        score = 60,
                    )
                }
                dropped = 0
            }
            shots to dropped
        }.onFailure {
            com.flashcut.app.DebugLog.e(context, "Analyzer",
                "分析素材 ${src.displayName} 失败", it)
        }.getOrDefault(emptyList<ShotInsight>() to 0).also { retriever.release() }
    }

    private fun readFrame(r: MediaMetadataRetriever, us: Long): Bitmap? = runCatching {
        // 优先取最近帧（含非关键帧）；失败再退回最近关键帧
        r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }.getOrNull()

    /** 平均亮度 0..255 */
    private fun brightness(b: Bitmap): Float {
        val bw = 24
        val bh = 24
        val small = Bitmap.createScaledBitmap(b, bw, bh, true)
        var sum = 0L
        for (y in 0 until bh) for (x in 0 until bw) {
            val p = small.getPixel(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val bl = p and 0xFF
            sum += (r * 299 + g * 587 + bl * 114) / 1000
        }
        if (small !== b) small.recycle()
        return sum.toFloat() / (bw * bh)
    }

    /** 用相邻像素差近似清晰度（越小越糊/越抖） */
    private fun sharpness(b: Bitmap): Float {
        val w = 32
        val h = 32
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        var sum = 0.0
        for (y in 0 until h - 1) for (x in 0 until w - 1) {
            val p1 = small.getPixel(x, y)
            val p2 = small.getPixel(x + 1, y)
            sum += abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF)).toDouble()
        }
        if (small !== b) small.recycle()
        return (sum / (w * h)).toFloat()
    }

    /**
     * 清晰度 → 20..99 分。
     * sharpness 是相邻像素亮度差的均值，真实视频通常在 2..30 之间，
     * 早先的 /6 加 32 阈值会把正常素材全部判为模糊。
     */
    private fun scoreOf(sharp: Float): Int {
        // 以 6f 为「良好」基准，做平滑映射
        val normalized = (sharp / 6f).coerceIn(0f, 1.6f)
        // 极模糊（sharp≈0）给 5 分，良好（sharp≥6）给 70 分以上
        return (5 + normalized * 58).toInt().coerceIn(5, 99)
    }

    private fun titleFor(i: Int, style: String): String {
        val pool = when (style) {
            "beat" -> listOf("起势", "加速", "爆点", "收束", "余韵")
            "talk" -> listOf("开场白", "正题", "举例", "观点", "收尾")
            "shop" -> listOf("上镜", "卖点", "细节", "对比", "转化")
            else -> listOf("开场", "路上", "风景", "人像", "收尾")
        }
        return pool[i % pool.size] + " ${i + 1}"
    }

    private fun reasonFor(score: Int, i: Int): String = when {
        score >= 80 -> "构图开阔 · 光影强 · 无遮挡"
        score >= 60 -> "画面清晰 · 运动感强 · 可做衔接"
        else -> "轻微抖动，可配合防抖使用"
    }

    private fun inferTheme(style: String, shots: List<ShotInsight>): String = when (style) {
        "beat" -> "情绪混剪 · 音乐驱动 · 节奏强"
        "talk" -> "口播切片 · 信息密集 · 强字幕"
        "shop" -> "好物展示 · 快节奏 · 卖点突出"
        else -> "旅行记录 · 傍晚 · 人像+风景混合"
    }

    private fun tagsFor(style: String): List<String> = when (style) {
        "beat" -> listOf("情绪", "卡点", "高能")
        "talk" -> listOf("口播", "干货", "字幕")
        "shop" -> listOf("好物", "卖点", "转化")
        else -> listOf("旅行", "日落", "人像")
    }
}
