package com.flashcut.app.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.ExportResult
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
 * 用 Media3 Transformer 在本地完成剪辑导出：
 * 1) 每段 TimelineClip 生成一个 EditedMediaItem（裁剪 + 统一高度）
 * 2) 组合成 Composition，按顺序拼接
 * 3) 硬件编码优先（H.264/AAC），失败提示降级
 */
class VideoExporter {

    data class Progress(val percent: Int, val message: String)

    suspend fun export(
        context: Context,
        project: Project,
        onProgress: (Progress) -> Unit,
    ): Result<File> = withContext(Dispatchers.Main) {
        val t0 = System.currentTimeMillis()
        val items = project.clips.mapNotNull { buildItem(project, it) }
        if (items.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("没有可导出的片段"))
        }

        val output = TmpManager.newExportFile(context, "FlashCut_${System.currentTimeMillis()}.mp4")
        if (output.exists()) output.delete()

        val error = AtomicReference<String?>(null)
        val finished = AtomicReference(false)

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
                    exportException: ExportException,
                ) {
                    com.flashcut.app.DebugLog.e(context, "Export", "渲染出错", exportException)
                    error.set(exportException.message ?: "导出失败")
                    finished.set(true)
                }
            })
            .build()

        runCatching {
            val sequence = EditedMediaItemSequence(items)
            val composition = Composition.Builder(listOf(sequence)).build()
            transformer.start(composition, output.absolutePath)
        }.onFailure {
            com.flashcut.app.DebugLog.e(context, "Export", "启动渲染失败", it)
            return@withContext Result.failure(it)
        }
        onProgress(Progress(0, "开始渲染"))

        val holder = ProgressHolder()
        while (!finished.get()) {
            val state = transformer.getProgress(holder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE && holder.progress in 1..100) {
                onProgress(Progress(holder.progress, "正在渲染 ${holder.progress}%"))
            }
            delay(150)
        }

        error.get()?.let {
            return@withContext Result.failure(IllegalStateException("导出失败：$it"))
        }
        if (!output.exists() || output.length() == 0L) {
            return@withContext Result.failure(IllegalStateException("导出文件为空，请降低分辨率后重试"))
        }
        onProgress(Progress(100, "导出完成"))
        Result.success(output)
    }

    private fun buildItem(project: Project, clip: TimelineClip): EditedMediaItem? {
        val source = project.sourceOf(clip) ?: return null
        val mediaItem = MediaItem.Builder()
            .setUri(source.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.inMs)
                    .setEndPositionMs(clip.outMs)
                    .build()
            )
            .build()

        val effects = Effects(emptyList(), listOf(Presentation.createForHeight(1280)))

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(effects)
            .setRemoveAudio(false)
            .build()
    }
}
