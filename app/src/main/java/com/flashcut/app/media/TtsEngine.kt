package com.flashcut.app.media

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.flashcut.app.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 配音合成。
 *
 * 之前配音页只是把文字存进内存，导出时完全不带音频，
 * App 自己也只能提示「TTS 引擎暂未接入」。
 * 这里接入系统 TTS，把文案真正合成为音频文件。
 */
class TtsEngine {

    data class Result(
        val file: File,
        val durationMs: Long,
        val engineName: String,
    )

    /**
     * 把文案合成为 wav 文件。
     *
     * @return 成功返回音频文件；设备没有可用 TTS 引擎时返回 failure
     */
    suspend fun synthesize(
        context: Context,
        text: String,
        speechRate: Float = 0.95f,
        pitch: Float = 1.0f,
    ): kotlin.Result<Result> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext kotlin.Result.failure(IllegalArgumentException("配音文案为空"))
        }

        val outFile = File(context.cacheDir, "tts_${System.currentTimeMillis()}.wav")
        if (outFile.exists()) outFile.delete()

        val ready = AtomicBoolean(false)
        var engineRef: TextToSpeech? = null

        val init = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val tts = TextToSpeech(context) { status ->
                    ready.set(status == TextToSpeech.SUCCESS)
                    if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS)
                }
                engineRef = tts
            }
        }

        val tts = engineRef
        if (init != true || tts == null) {
            runCatching { tts?.shutdown() }
            DebugLog.e(context, "TTS", "引擎初始化失败", Exception("init=$init"))
            return@withContext kotlin.Result.failure(
                IllegalStateException("设备没有可用的语音合成引擎，请在系统设置里启用 TTS")
            )
        }

        try {
            val langResult = tts.setLanguage(Locale.CHINA)
            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                tts.shutdown()
                return@withContext kotlin.Result.failure(
                    IllegalStateException("当前 TTS 引擎不支持中文，请安装中文语音包")
                )
            }
            tts.setSpeechRate(speechRate)
            tts.setPitch(pitch)

            val done = suspendCancellableCoroutine<Boolean> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    @Deprecated("兼容旧接口")
                    override fun onError(utteranceId: String?) {
                        if (cont.isActive) cont.resume(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (cont.isActive) cont.resume(false)
                    }
                })

                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "flashcut_narration")
                }
                val queued = tts.synthesizeToFile(text, params, outFile, "flashcut_narration")
                if (queued != TextToSpeech.SUCCESS) {
                    if (cont.isActive) cont.resume(false)
                }
            }

            if (!done || !outFile.exists() || outFile.length() < 1000) {
                tts.shutdown()
                return@withContext kotlin.Result.failure(
                    IllegalStateException("语音合成没有产出有效音频")
                )
            }

            val dur = probeWavDurationMs(outFile)
            DebugLog.i(context, "TTS", "配音合成完成 ${outFile.length() / 1024}KB ${dur}ms")
            kotlin.Result.success(Result(outFile, dur, tts.defaultEngine ?: "system"))
        } catch (e: Exception) {
            DebugLog.e(context, "TTS", "合成异常", e)
            kotlin.Result.failure(e)
        } finally {
            runCatching { tts.shutdown() }
        }
    }

    /** 从 wav 头读时长，避免额外的解码开销 */
    private fun probeWavDurationMs(f: File): Long = runCatching {
        val bytes = f.inputStream().use { it.readNBytes(64) }
        if (bytes.size < 44) return@runCatching 0L
        fun le32(off: Int): Long =
            (bytes[off].toLong() and 0xff) or
                ((bytes[off + 1].toLong() and 0xff) shl 8) or
                ((bytes[off + 2].toLong() and 0xff) shl 16) or
                ((bytes[off + 3].toLong() and 0xff) shl 24)

        val byteRate = le32(28)
        val dataSize = f.length() - 44
        if (byteRate <= 0) 0L else dataSize * 1000 / byteRate
    }.getOrDefault(0L)

    /** 系统有没有可用引擎，用于在界面上给出准确提示 */
    fun hasEngine(context: Context): Boolean = runCatching {
        val tts = TextToSpeech(context) {}
        val ok = tts.defaultEngine != null
        tts.shutdown()
        ok
    }.getOrDefault(false)
}
