package com.flashcut.app

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志：同时写到 logcat 和 /sdcard/Download/DSHA/flashcut-debug.log。
 * 这样在容器里可以直接读文件排查界面与接口问题，不必靠截图。
 */
object DebugLog {

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val buffer = StringBuilder()

    /** 优先写 App 私有目录（无需权限）；再尝试公共 Download 方便导出 */
    private fun logFile(context: Context): File? = runCatching {
        val priv = File(context.getExternalFilesDir(null) ?: context.filesDir, "flashcut-debug.log")
        priv
    }.getOrNull()

    private fun publicLogFile(): File? = runCatching {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DSHA")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "flashcut-debug.log")
    }.getOrNull()

    fun i(context: Context, tag: String, msg: String) {
        val line = "${fmt.format(Date())} I/$tag: $msg"
        android.util.Log.i(tag, msg)
        append(context, line)
    }

    fun e(context: Context, tag: String, msg: String, t: Throwable? = null) {
        val line = "${fmt.format(Date())} E/$tag: $msg" + (t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        if (t != null) android.util.Log.e(tag, msg, t) else android.util.Log.e(tag, msg)
        append(context, line)
        t?.stackTraceToString()?.lines()?.take(12)?.forEach { append(context, "    at $it") }
    }

    @Synchronized
    private fun append(context: Context, line: String) {
        buffer.appendLine(line)
        if (buffer.length > 40_000) buffer.delete(0, buffer.length - 30_000)
        runCatching { logFile(context)?.appendText(line + "\n") }
        runCatching { publicLogFile()?.appendText(line + "\n") }
    }

    fun recent(): String = buffer.toString()
}
