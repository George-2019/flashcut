package com.flashcut.app.tmp

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * 统一管理临时工作目录，所有中间产物（解码段、拼接结果、导出文件）都放在这里。
 * 导入时只拿 content:// URI，不复制原片；需要随机访问时才转成临时文件。
 */
object TmpManager {
    const val DIR = "flashcut_tmp"

    fun root(context: Context): File =
        File(context.cacheDir, DIR).apply { mkdirs() }

    fun newWorkDir(context: Context): File {
        val dir = File(root(context), "job_" + UUID.randomUUID().toString().take(8))
        dir.mkdirs()
        return dir
    }

    fun newExportFile(context: Context, name: String): File {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        return File(dir, name)
    }

    fun clearAll(context: Context) {
        runCatching { root(context).deleteRecursively() }
    }
}
