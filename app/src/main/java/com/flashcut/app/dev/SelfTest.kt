package com.flashcut.app.dev

import android.content.Context
import android.net.Uri
import com.flashcut.app.DebugLog
import com.flashcut.app.ai.MaterialBrief
import com.flashcut.app.ai.ScriptGenerator
import com.flashcut.app.ai.ShotAnalyzer
import com.flashcut.app.data.SourceClip
import com.flashcut.app.llm.LlmClient
import com.flashcut.app.llm.LlmConfigStore
import com.flashcut.app.llm.LlmResult
import com.flashcut.app.media.MediaProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 内置自检：在真机上一次跑通「读素材 → 镜头分析 → 大模型脚本」，
 * 每步结果写进 debug 日志，供外部读取定位问题。
 *
 * 触发方式：Settings.Secure 中设置 flashcut.selftest，或由 DebugActivity 调用。
 */
object SelfTest {

    suspend fun run(context: Context, videoUris: List<Uri>): String = withContext(Dispatchers.Default) {
        val sb = StringBuilder()
        fun line(s: String) {
            sb.appendLine(s)
        }

        line("=== 自检开始 ===")

        // 1. 配置
        val store = LlmConfigStore(context)
        val profile = store.loadActive()
        val key = store.apiKey(profile.id)
        line("配置: ${profile.id} url=${profile.baseUrl} model=${profile.model} keyLen=${key?.length ?: 0}")

        // 2. 素材读取
        if (videoUris.isEmpty()) {
            line("素材: 无可测试视频（跳过分析）")
        } else {
            val clips = MediaProbe.probeAll(context, videoUris)
            line("素材: 读入 ${clips.size}/${videoUris.size} 段 ${clips.map { it.durationMs }}")

            if (clips.isNotEmpty()) {
                // 2.5 抽帧诊断：看看到底拿到了几帧、清晰度多少
                val c0 = clips.first()
                val mmr = android.media.MediaMetadataRetriever()
                runCatching {
                    mmr.setDataSource(context, c0.uri)
                    val dur = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    val w = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    line("视频元数据: 时长=${dur}ms 尺寸=${w}x${h}")
                    var got = 0
                    val vals = mutableListOf<String>()
                    for (t in listOf(0L, 1000L, 3000L, 5000L, 8000L, 12000L)) {
                        val bm = mmr.getFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                        if (bm != null) {
                            got++
                            if (vals.size < 6) vals += "t=${t}ms ${bm.width}x${bm.height}"
                            bm.recycle()
                        }
                    }
                    line("抽帧测试: 6 个时间点拿到 $got 帧 ${vals}")
                }.onFailure { line("抽帧测试失败: ${it.javaClass.simpleName} ${it.message}") }
                runCatching { mmr.release() }

                // 3. 镜头分析
                val analyzer = ShotAnalyzer(context)
                val t0 = System.currentTimeMillis()
                val brief: MaterialBrief = analyzer.analyze(clips, "vlog") { }
                val cost = System.currentTimeMillis() - t0
                line("分析: ${brief.totalShots} 个镜头, 剔除 ${brief.jitterDropped}, 耗时 ${cost}ms")
                line("主题: ${brief.theme} 置信度=${brief.confidence}")
                brief.shots.take(3).forEach {
                    line("  镜头 ${it.id} ${it.startMs}-${it.endMs} score=${it.score}")
                }

                // 4. 大模型脚本
                if (!key.isNullOrBlank()) {
                    val gen = ScriptGenerator()
                    val t1 = System.currentTimeMillis()
                    when (val r = gen.generate(profile, key, brief, "vlog", 30)) {
                        is LlmResult.Ok -> {
                            val s = r.value
                            line("LLM: OK 耗时 ${System.currentTimeMillis() - t1}ms")
                            line("  标题: ${s.title}")
                            line("  解说词: ${s.narration.take(50)}")
                            line("  cuts=${s.cuts.size} 总时长=${s.durationMs}ms")
                            s.cuts.take(3).forEach { c ->
                                line("    cut ${c.shotId} ${c.inMs}-${c.outMs} cap='${c.caption}'")
                            }
                        }
                        is LlmResult.Err -> line("LLM: 失败 ${r.message}（耗时 ${System.currentTimeMillis() - t1}ms）")
                    }
                } else {
                    line("LLM: 跳过（未配置 Key）")
                }
            }
        }

        line("=== 自检结束 ===")
        val text = sb.toString()
        DebugLog.i(context, "SelfTest", text.replace("\n", " ⏎ "))
        text
    }

    /** 阻塞版，供 Activity 直接调用 */
    fun runBlocking(context: Context, uris: List<Uri>): String =
        runBlocking { run(context, uris) }
}
