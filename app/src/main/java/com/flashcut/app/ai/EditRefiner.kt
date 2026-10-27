package com.flashcut.app.ai

import kotlin.math.abs

/**
 * 剪辑脚本后处理：把大模型给的粗糙 cuts 修正成可用的成片方案。
 *
 * 模型常见问题（实测遇到过）：
 *  - 同一个镜头切好几段，画面重复
 *  - 时长不收敛，20 秒目标给出 45 秒
 *  - 字幕空缺
 *  - 全挤在同一个素材里，缺乏层次
 * 这些用代码兜住，比反复调提示词可靠。
 */
object EditRefiner {

    private const val MIN_CLIP_MS = 700L      // 短于此的片段观感很差
    private const val SOFT_MARGIN = 0.12      // 目标时长允许 ±12%

    fun refine(
        cuts: List<ScriptCut>,
        brief: MaterialBrief,
        targetSec: Int,
        style: String,
    ): EditScript {
        val byId = brief.shots.associateBy { it.id }
        val targetMs = targetSec * 1000L

        // 1) 去重：同一镜头只保留信息量最大的一段（优先最长，其次分数高）
        val dedup = linkedMapOf<String, ScriptCut>()
        cuts.sortedWith(
            compareByDescending<ScriptCut> { it.outMs - it.inMs }
                .thenByDescending { byId[it.shotId]?.score ?: 0 }
        ).forEach { c -> dedup.putIfAbsent(c.shotId, c) }

        // 2) 时长收敛：超了就按比例裁，不够就补高分镜头
        var picked = dedup.values.toMutableList()
        val total = picked.sumOf { it.outMs - it.inMs }
        val tolerance = (targetMs * (1 + SOFT_MARGIN)).toLong()

        if (total > tolerance) {
            val ratio = targetMs.toDouble() / total
            picked = picked.map { c ->
                val dur = ((c.outMs - c.inMs) * ratio).toLong().coerceAtLeast(MIN_CLIP_MS)
                c.copy(outMs = c.inMs + dur)
            }.toMutableList()
        } else if (total < targetMs * (1 - SOFT_MARGIN)) {
            // 补镜头：还没用过的、分数高的
            val used = picked.map { it.shotId }.toHashSet()
            val spare = brief.shots
                .filter { it.id !in used }
                .sortedByDescending { it.score }
            var need = targetMs - total
            for (s in spare) {
                if (need <= MIN_CLIP_MS) break
                val take = minOf(s.durationMs, need, 5000L)
                if (take < MIN_CLIP_MS) continue
                picked += ScriptCut(s.id, s.sourceId, 0, take, captionOf(s), 1f)
                need -= take
            }
        }

        // 3) 排序：按分数做情绪曲线，最高分前置（前三秒抓人）
        picked = picked.sortedWith(
            compareByDescending { byId[it.shotId]?.score ?: 50 }
        ).toMutableList()

        // 4) 字幕兜底 + 时长下限
        val finalCuts = picked.map { c ->
            val s = byId[c.shotId]
            val safeOut = if (c.outMs - c.inMs < MIN_CLIP_MS)
                (c.inMs + MIN_CLIP_MS).coerceAtMost(s?.durationMs ?: c.outMs)
            else c.outMs
            c.copy(
                outMs = safeOut,
                caption = c.caption.ifBlank { captionOf(s) },
            )
        }.filter { it.outMs > it.inMs }

        return EditScript(
            title = "",
            style = style,
            targetDurationSec = targetSec,
            cuts = if (finalCuts.isEmpty()) fallbackCuts(brief, targetSec) else finalCuts,
        )
    }

    /** 用镜头现有的标题当字幕，比空着好 */
    private fun captionOf(s: ShotInsight?): String {
        val t = s?.title?.trim().orEmpty()
        if (t.isBlank()) return ""
        return t.take(16)
    }

    /** 取镜头高光段：中间 60%，比从头切更像人剪的 */
    fun highlight(s: ShotInsight, maxMs: Long = 5000): Pair<Long, Long> {
        val dur = s.durationMs
        if (dur <= maxMs) return 0L to dur
        val center = dur / 2
        val half = maxMs / 2
        val start = (center - half).coerceAtLeast(0)
        val end = (start + maxMs).coerceAtMost(dur)
        return start to end
    }

    /** 兜底：按分数排序取高光段，跨素材均衡 */
    private fun fallbackCuts(brief: MaterialBrief, targetSec: Int): List<ScriptCut> {
        val budget = targetSec * 1000L
        val out = mutableListOf<ScriptCut>()
        var used = 0L
        // 轮转各素材，避免全来自同一个文件
        val bySource = brief.shots.sortedByDescending { it.score }.groupBy { it.sourceId }
        val queues = bySource.values.map { it.toMutableList() }.toMutableList()
        var turn = 0
        while (used < budget && queues.any { it.isNotEmpty() }) {
            val q = queues[turn % queues.size.coerceAtLeast(1)]
            turn++
            if (q.isEmpty()) continue
            val s = q.removeAt(0)
            val (st, en) = highlight(s, minOf(budget - used, 5000L))
            if (en - st < MIN_CLIP_MS) continue
            out += ScriptCut(s.id, s.sourceId, st, en, captionOf(s), 1f)
            used += en - st
        }
        return out
    }

    /** 校验成片是否达标，返回问题描述 */
    fun audit(script: EditScript, targetSec: Int): String? {
        val ms = script.durationMs
        val target = targetSec * 1000L
        if (script.cuts.isEmpty()) return "没有可用片段"
        if (abs(ms - target) > target * 0.25) {
            return "时长偏离较大：实际 ${ms / 1000}s，目标 ${targetSec}s"
        }
        val ids = script.cuts.map { it.shotId }
        if (ids.size != ids.distinct().size) return "存在重复镜头"
        return null
    }
}
