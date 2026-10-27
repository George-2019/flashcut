package com.flashcut.app.ai

/** 单个镜头的分析结论，会作为大模型的输入（而不是让模型重新看原片）。 */
data class ShotInsight(
    val id: String,
    val sourceId: String,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val reason: String,
    val score: Int,
    val tags: List<String> = emptyList(),
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/** 素材整体理解结果。 */
data class MaterialBrief(
    val theme: String = "",
    val confidence: Int = 0,
    val tags: List<String> = emptyList(),
    val shots: List<ShotInsight> = emptyList(),
    val speech: String = "",
    val speechSegments: Int = 0,
    val speechSeconds: Int = 0,
    val jitterDropped: Int = 0,
) {
    val totalShots: Int get() = shots.size
}

/** 大模型产出的剪辑脚本（EDL 的简化形态）。 */
data class EditScript(
    val title: String = "",
    val style: String = "",
    val targetDurationSec: Int = 30,
    val narration: String = "",
    val music: String = "",
    val effectNote: String = "",
    val captions: List<String> = emptyList(),
    val cuts: List<ScriptCut> = emptyList(),
) {
    val durationMs: Long get() = cuts.sumOf { (it.outMs - it.inMs).coerceAtLeast(0) }
}

data class ScriptCut(
    val shotId: String,
    val sourceId: String,
    val inMs: Long,
    val outMs: Long,
    val caption: String = "",
    val speed: Float = 1f,
)

/** AI 做了什么，用于成片页说明。 */
data class ComposeSummary(
    val clipCount: Int = 0,
    val sourceCount: Int = 0,
    val effectCount: Int = 0,
    val narrationSec: Int = 0,
    val lines: List<String> = emptyList(),
)
