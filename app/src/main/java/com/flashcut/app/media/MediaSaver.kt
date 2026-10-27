package com.flashcut.app.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.flashcut.app.DebugLog
import java.io.File

/** 把导出的成片写入系统相册（Movies/FlashCut），用户能在相册/文件管理器里找到。 */
object MediaSaver {

    private const val RELATIVE_DIR = "Movies/FlashCut"

    fun saveToGallery(context: Context, src: File): Boolean {
        val name = src.name
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return false

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                DebugLog.i(context, "Saver", "已存入相册 $RELATIVE_DIR/$name")
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "FlashCut"
                ).apply { mkdirs() }
                val dst = File(dir, name)
                src.copyTo(dst, overwrite = true)
                // 通知系统媒体库扫描，否则相册里看不到
                runCatching {
                    android.media.MediaScannerConnection.scanFile(
                        context, arrayOf(dst.absolutePath), arrayOf("video/mp4"), null
                    )
                }
                DebugLog.i(context, "Saver", "已存入 $dst")
                true
            }
        }.onFailure {
            DebugLog.e(context, "Saver", "存入相册失败", it)
        }.getOrDefault(false)
    }
}
