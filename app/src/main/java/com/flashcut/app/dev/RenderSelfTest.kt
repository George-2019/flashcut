package com.flashcut.app.dev

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.flashcut.app.DebugLog
import com.flashcut.app.data.Project
import com.flashcut.app.data.TimelineClip
import com.flashcut.app.media.EffectPreset
import com.flashcut.app.media.ExportOptions
import com.flashcut.app.media.MusicSynth
import com.flashcut.app.media.TtsEngine
import com.flashcut.app.media.VideoExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 渲染自检：不需要用户点任何按钮，一次跑完所有渲染能力并写日志。
 *
 * 只验证「组件是否真的产出结果」，比人工点界面更可靠：
 *  1) 配乐能不能生成真实音频
 *  2) TTS 能不能合成真实语音
 *  3) 字幕位图有没有画出字
 *  4) 各特效预设能不能建出效果链
 *  5) 完整导出能不能出片（含特效+字幕+配音+配乐）
 */
object RenderSelfTest {

    suspend fun run(context: Context, sampleVideo: Uri?): String = withContext(Dispatchers.Default) {
        val sb = StringBuilder()
        fun line(s: String) {
            sb.appendLine(s)
            DebugLog.i(context, "RenderTest", s)
        }

        line("=== 渲染自检开始 ===")

        // 1. 配乐
        val music = MusicSynth().synthesize(context, MusicSynth.Style.LIGHT, 5000)
        val musicFile = music.getOrNull()
        line(
            if (musicFile != null && musicFile.length() > 100_000)
                "① 配乐: OK ${musicFile.length() / 1024}KB"
            else
                "① 配乐: FAIL ${music.exceptionOrNull()?.message}"
        )

        // 2. TTS
        val tts = TtsEngine().synthesize(context, "这是一段配音测试，用于验证语音合成是否可用。")
        val ttsRes = tts.getOrNull()
        line(
            if (ttsRes != null)
                "② 配音: OK ${ttsRes.file.length() / 1024}KB ${ttsRes.durationMs}ms"
            else
                "② 配音: FAIL ${tts.exceptionOrNull()?.message}"
        )

        // 3. 字幕位图
        val bmp = com.flashcut.app.media.TextOverlayFactory.renderTextBitmap(
            "萌娃第一次学走路", 720, 1280
        )
        // 检查是否真有非透明像素（说明字画上去了）
        val hasPixels = withContext(Dispatchers.Default) {
            var count = 0
            outer@ for (x in 0 until bmp.width step 8) {
                for (y in 0 until bmp.height step 8) {
                    if (bmp.getPixel(x, y) != 0) {
                        count++
                        if (count > 20) break@outer
                    }
                }
            }
            count
        }
        line("③ 字幕位图: ${if (hasPixels > 20) "OK 非空像素=$hasPixels" else "FAIL 空白"}")

        // 4. 特效链
        val fx = EffectPreset.entries.joinToString { it ->
            "${it.id}=${it.buildEffects().size}"
        }
        line("④ 特效链: $fx")

        // 5. 完整导出
        if (sampleVideo == null) {
            line("⑤ 完整导出: SKIP 没有可用测试视频")
        } else {
            val dur = probeDuration(context, sampleVideo)
            if (dur < 1500) {
                line("⑤ 完整导出: SKIP 视频太短(${dur}ms)")
            } else {
                val half = dur / 2
                val project = Project(
                    id = "selftest",
                    name = "渲染自检",
                    sources = listOf(
                        com.flashcut.app.data.SourceClip(
                            id = "src1", uri = sampleVideo,
                            displayName = "selftest", durationMs = dur,
                        )
                    ),
                    clips = listOf(
                        TimelineClip("c1", "src1", 0, half, 1f, "自检字幕第一段"),
                        TimelineClip("c2", "src1", half, dur, 1f, "自检字幕第二段"),
                    ),
                )
                val opts = ExportOptions(
                    effectPreset = EffectPreset.WARM,
                    burnSubtitles = true,
                    narrationText = "这是自检配音",
                    musicStyle = MusicSynth.Style.LIGHT,
                )
                val out = VideoExporter().export(context, project, opts) { }
                val f = out.getOrNull()
                line(
                    if (f != null && f.length() > 50_000)
                        "⑤ 完整导出: OK ${f.length() / 1024}KB ${f.name}"
                    else
                        "⑤ 完整导出: FAIL ${out.exceptionOrNull()?.message}"
                )
            }
        }

        line("=== 渲染自检结束 ===")
        sb.toString()
    }

    private fun probeDuration(context: Context, uri: Uri): Long = runCatching {
        val mmr = android.media.MediaMetadataRetriever()
        mmr.setDataSource(context, uri)
        val ms = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        mmr.release()
        ms
    }.getOrDefault(0L)
}
