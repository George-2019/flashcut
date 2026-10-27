package com.flashcut.app.media

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.flashcut.app.data.Project
import com.flashcut.app.data.TimelineClip
import com.flashcut.app.tmp.TmpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 导出引擎：把时间线渲染成成片。
 *
 * 这一版真正接入了：
 *  1) 特效     —— EffectPreset 生成 GlEffect 链，逐帧调色
 *  2) 字幕     —— OverlayEffect 把文字烧进画面
 *  3) 配音     —— TtsEngine 合成后作为独立音频轨混入
 *  4) 配乐     —— MusicSynth 生成后作为背景音轨混入
 */
@UnstableApi
class VideoExporter {

    data class Progress(val percent: Int, val message: String)

    /**
     * @param options 特效/字幕/配音/配乐配置
     * @param onPrepare 需要合成音频时的进度回调（TTS 比较慢）
     */
    suspend fun export(
        context: Context,
        project: Project,
        options: ExportOptions = ExportOptions(),
        onPrepare: (String) -> Unit = {},
        onProgress: (Progress) -> Unit,
    ): Result<File> = withContext(Dispatchers.Main) {
        val t0 = System.currentTimeMillis()

        val validClips = project.clips.filter { it.outMs > it.inMs && project.sourceOf(it) != null }
        if (validClips.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("没有可导出的片段"))
        }

        val totalMs = validClips.sumOf { it.durationMs }
        val w = options.targetWidth
        val h = options.targetHeight

        // ---------- 1. 画面轨：每段加特效 + 字幕 ----------
        val baseEffects = options.effectPreset.buildEffects()
        val items = mutableListOf<EditedMediaItem>()

        val overlap = TransitionFactory.overlapMs(options.transition)

        for ((idx, clip) in validClips.withIndex()) {
            val source = project.sourceOf(clip) ?: continue

            // 转场靠裁掉片段末尾一点制造停顿，太短的片段不再裁
            val endMs = if (overlap > 0 && clip.outMs - clip.inMs > overlap + 600) {
                clip.outMs - overlap
            } else {
                clip.outMs
            }

            val mediaItem = MediaItem.Builder()
                .setUri(source.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.inMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()

            val chain = mutableListOf<Effect>()
            chain += baseEffects
            // 非首段加转场效果
            if (TransitionFactory.needsFadeIn(options.transition, idx)) {
                chain += TransitionFactory.effects(options.transition, idx)
            }
            // 统一成竖屏尺寸
            chain += Presentation.createForHeight(h)
            // 烧录字幕
            if (options.burnSubtitles) {
                TextOverlayFactory.forClip(clip.caption, w, h)?.let { chain += it }
            }

            // 原声按配置降到背景声（保留环境音，不喧宾夺主）
            val audioChain = if (options.originalVolume < 0.999f) {
                listOf<androidx.media3.common.audio.AudioProcessor>(
                    GainProcessor(options.originalVolume)
                )
            } else {
                emptyList()
            }

            items += EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(audioChain, chain))
                .setRemoveAudio(false)
                .build()
        }

        if (items.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("没有可渲染的片段"))
        }

        val videoSeq = EditedMediaItemSequence.Builder(items).build()
        val sequences = mutableListOf(videoSeq)

        // ---------- 2. 配音轨 ----------
        onPrepare("准备配音…")
        var narrationFile: File? = null
        if (options.narrationText.isNotBlank()) {
            val tts = TtsEngine()
            val r = tts.synthesize(context, options.narrationText)
            narrationFile = r.getOrNull()?.file
            if (narrationFile == null) {
                com.flashcut.app.DebugLog.e(context, "Export", "配音失败，跳过", r.exceptionOrNull())
            }
        }

        // ---------- 3. 配乐轨 ----------
        onPrepare("准备配乐…")
        var musicFile: File? = null
        options.musicStyle?.let { style ->
            val r = MusicSynth().synthesize(context, style, totalMs)
            musicFile = r.getOrNull()
            if (musicFile == null) {
                com.flashcut.app.DebugLog.e(context, "Export", "配乐失败，跳过", r.exceptionOrNull())
            }
        }

        // 音频轨：配音在先（人声优先），配乐在后
        narrationFile?.let { f ->
            val gain = if (options.narrationVolume < 0.999f) {
                listOf<androidx.media3.common.audio.AudioProcessor>(GainProcessor(options.narrationVolume))
            } else {
                emptyList()
            }
            sequences += EditedMediaItemSequence.Builder(
                EditedMediaItem.Builder(MediaItem.fromUri(f.toURI().toString()))
                    .setEffects(Effects(gain, emptyList()))
                    .build()
            ).build()
        }
        musicFile?.let { f ->
            sequences += EditedMediaItemSequence.Builder(
                EditedMediaItem.Builder(MediaItem.fromUri(f.toURI().toString()))
                    .setEffects(
                        Effects(
                            listOf<androidx.media3.common.audio.AudioProcessor>(
                                GainProcessor(options.musicVolume)
                            ),
                            emptyList(),
                        )
                    )
                    .build()
            ).build()
        }

        com.flashcut.app.DebugLog.i(
            context, "Export",
            "开始渲染 clips=${items.size} 特效=${options.effectPreset.label} " +
                "字幕=${options.burnSubtitles} 配音=${narrationFile != null} 配乐=${musicFile != null}"
        )

        val output = TmpManager.newExportFile(context, "FlashCut_${System.currentTimeMillis()}.mp4")
        if (output.exists()) output.delete()

        val error = AtomicReference<String?>(null)
        val finished = AtomicReference(false)

        val composition = Composition.Builder(sequences).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    com.flashcut.app.DebugLog.i(context, "Export", "渲染完成 ${output.name}")
                    finished.set(true)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exception: ExportException,
                ) {
                    val msg = describe(exception)
                    com.flashcut.app.DebugLog.e(context, "Export", "渲染失败: $msg", exception)
                    error.set(msg)
                    finished.set(true)
                }
            })
            .build()

        transformer.start(composition, output.absolutePath)

        val holder = ProgressHolder()
        var lastPct = -1
        while (!finished.get()) {
            val state = transformer.getProgress(holder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE && holder.progress != lastPct) {
                lastPct = holder.progress
                onProgress(Progress(holder.progress.coerceIn(0, 99), "渲染中 ${holder.progress}%"))
            }
            delay(120)
        }
        transformer.cancel()

        error.get()?.let { return@withContext Result.failure(IllegalStateException(it)) }
        if (!output.exists() || output.length() < 1024) {
            return@withContext Result.failure(IllegalStateException("导出文件为空，请降低分辨率后重试"))
        }

        onProgress(Progress(100, "导出完成"))
        com.flashcut.app.DebugLog.i(
            context, "Export",
            "导出成功 ${output.length() / 1024}KB 耗时 ${(System.currentTimeMillis() - t0) / 1000}s"
        )
        Result.success(output)
    }

    private fun describe(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "素材编码不受支持，请换一个视频"
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> "设备不支持该输出格式"
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件已被移动或删除"
        ExportException.ERROR_CODE_MUXING_FAILED -> "音视频合成失败"
        else -> "渲染失败（code=${e.errorCode}）：${e.message?.take(120).orEmpty()}"
    }
}
