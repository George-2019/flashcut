package com.flashcut.app.data

import android.net.Uri

/** 用户导入的一段原始素材。只保存 URI，不复制文件。 */
data class SourceClip(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val width: Int = 0,
    val height: Int = 0
)

/** 时间线上的一段剪辑：引用某个源素材的 [inMs, outMs) 区间。 */
data class TimelineClip(
    val id: String,
    val sourceId: String,
    val inMs: Long,
    val outMs: Long,
    val speed: Float = 1f,
    /** 该片段显示的字幕，来自 AI 剪辑脚本的 caption */
    val caption: String = "",
) {
    val durationMs: Long
        get() = ((outMs - inMs) / speed).toLong()
}

/** 极简工程模型：MVP 只做单视频轨顺序拼接，后续再加字幕/音频/特效轨。 */
data class Project(
    val id: String,
    val name: String,
    val sources: List<SourceClip>,
    val clips: List<TimelineClip>,
) {
    val totalDurationMs: Long get() = clips.sumOf { it.durationMs }

    fun sourceOf(clip: TimelineClip): SourceClip? = sources.firstOrNull { it.id == clip.sourceId }

    companion object {
        fun fromSources(id: String, name: String, sources: List<SourceClip>): Project {
            // 默认每个片段取源素材的 0..min(时长, 8s)，避免长素材一次全塞进成片
            val clips = sources.map { src ->
                TimelineClip(
                    id = "clip_" + src.id,
                    sourceId = src.id,
                    inMs = 0,
                    outMs = minOf(src.durationMs, 8_000L),
                )
            }
            return Project(id = id, name = name, sources = sources, clips = clips)
        }
    }
}
