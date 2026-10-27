package com.flashcut.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.flashcut.app.data.SourceClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** 读取素材时长、分辨率等基础信息，供时间线和导出使用。 */
object MediaProbe {

    suspend fun probeAll(context: Context, uris: List<Uri>): List<SourceClip> =
        withContext(Dispatchers.IO) {
            uris.mapNotNull { uri -> probe(context, uri) }
        }

    fun probe(context: Context, uri: Uri): SourceClip? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            if (duration <= 0) return null
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val name = queryDisplayName(context, uri)
            SourceClip(
                id = UUID.randomUUID().toString().take(8),
                uri = uri,
                displayName = name,
                durationMs = duration,
                width = width,
                height = height,
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment.orEmpty()
        var name = "视频"
        runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
        }
        return name
    }

    /** 取第一帧做缩略图，失败返回 null。 */
    fun thumbnail(context: Context, uri: Uri, targetPx: Int = 160): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { bmp ->
                    val w = bmp.width
                    val h = bmp.height
                    if (w <= 0 || h <= 0) return@runCatching null
                    val scale = targetPx.toFloat() / minOf(w, h)
                    val nw = (w * scale).toInt().coerceAtLeast(1)
                    val nh = (h * scale).toInt().coerceAtLeast(1)
                    val out = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                    if (out !== bmp) bmp.recycle()
                    out
                }
        } finally {
            r.release()
        }
    }.getOrNull()

    fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }
}
