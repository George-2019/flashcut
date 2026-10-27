package com.flashcut.app.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 音量处理器：对经过的 PCM 数据做增益。
 *
 * media3 的 Mixer 会把多条音轨加在一起，但不会替我们做「配音大声、配乐小声」
 * 这种相对音量控制。这里用 AudioProcessor 在每条音轨上分别调节振幅，
 * 实现真正的混音比例。
 */
class GainProcessor(private val linearGain: Float) : BaseAudioProcessor() {

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        // 只处理 16bit PCM，其他格式原样放行
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)

        val g = linearGain.coerceIn(0f, 4f)
        // 两个字节一个采样，小端
        while (inputBuffer.remaining() >= 2) {
            val lo = inputBuffer.get().toInt() and 0xff
            val hi = inputBuffer.get().toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val scaled = (sample * g).roundToInt().coerceIn(-32768, 32767)
            out.put((scaled and 0xff).toByte())
            out.put(((scaled shr 8) and 0xff).toByte())
        }
        // 如果剩了奇数个字节，原样带上
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }
}
