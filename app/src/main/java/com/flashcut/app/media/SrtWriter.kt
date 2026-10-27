package com.flashcut.app.media

import com.flashcut.app.ai.ScriptCut
import com.flashcut.app.data.TimelineClip
import java.io.File
import java.util.Locale

/** 把剪辑脚本的 caption 写成标准 SRT 字幕。 */
object SrtWriter {

    fun build(cuts: List<ScriptCut>): String {
        val sb = StringBuilder()
        var cursor = 0L
        var idx = 1
        for (c in cuts) {
            val dur = (c.outMs - c.inMs).coerceAtLeast(500)
            val start = cursor
            val end = cursor + dur
            cursor = end
            if (c.caption.isBlank()) continue
            sb.append(idx++).append('\n')
            sb.append(fmt(start)).append(" --> ").append(fmt(end)).append('\n')
            sb.append(c.caption.trim()).append("\n\n")
        }
        return sb.toString()
    }

    /** 直接从时间线片段生成，字幕时长为该片段实际时长 */
    fun writeFromClips(file: File, clips: List<TimelineClip>): File {
        val sb = StringBuilder()
        var cursor = 0L
        var idx = 1
        for (c in clips) {
            val dur = c.durationMs.coerceAtLeast(300)
            val start = cursor
            cursor += dur
            if (c.caption.isBlank()) continue
            sb.append(idx++).append('\n')
            sb.append(fmt(start)).append(" --> ").append(fmt(cursor)).append('\n')
            sb.append(c.caption.trim()).append("\n\n")
        }
        file.writeText(sb.toString())
        return file
    }

    fun write(file: File, cuts: List<ScriptCut>): File {
        file.writeText(build(cuts))
        return file
    }

    private fun fmt(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val milli = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }
}
