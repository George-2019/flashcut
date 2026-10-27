package com.flashcut.app.media

import android.content.Context
import com.flashcut.app.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 内置配乐合成。
 *
 * 之前「音乐轨」只是界面上的一行文字，没有任何音频。
 * 这里用正弦合成生成真实可用的背景音乐 wav：
 * 按风格给出不同的和弦进行、节奏与音色，导出时混进成片。
 */
class MusicSynth {

    /** 可选的配乐风格 */
    enum class Style(val id: String, val label: String, val bpm: Int) {
        LIGHT("light", "轻快电子", 110),
        WARM("warm", "温暖钢琴", 82),
        ENERGY("energy", "燃向鼓点", 128),
        CALM("calm", "舒缓氛围", 70),
        ;

        companion object {
            fun of(id: String?): Style = entries.firstOrNull { it.id == id } ?: LIGHT
        }
    }

    /**
     * 合成一段配乐。
     *
     * @param durationMs 需要的总时长，会自动循环和弦进行
     */
    suspend fun synthesize(
        context: Context,
        style: Style,
        durationMs: Long,
    ): kotlin.Result<File> = withContext(Dispatchers.IO) {
        try {
            val sr = 44100
            val totalSamples = (durationMs * sr / 1000).toInt().coerceAtLeast(sr)
            val out = File(context.cacheDir, "music_${style.id}_${System.currentTimeMillis()}.wav")

            val chords = when (style) {
                Style.LIGHT -> listOf(
                    intArrayOf(261, 329, 392), // C
                    intArrayOf(220, 261, 329), // Am
                    intArrayOf(349, 440, 523), // F
                    intArrayOf(392, 493, 587), // G
                )
                Style.WARM -> listOf(
                    intArrayOf(261, 329, 392),
                    intArrayOf(196, 246, 293),
                    intArrayOf(220, 261, 329),
                    intArrayOf(174, 220, 261),
                )
                Style.ENERGY -> listOf(
                    intArrayOf(220, 277, 329),
                    intArrayOf(246, 311, 370),
                    intArrayOf(196, 246, 293),
                    intArrayOf(261, 329, 392),
                )
                Style.CALM -> listOf(
                    intArrayOf(261, 329, 392),
                    intArrayOf(233, 293, 349),
                    intArrayOf(220, 261, 329),
                    intArrayOf(196, 246, 293),
                )
            }

            val beatSamples = (60.0 / style.bpm * sr).toInt().coerceAtLeast(1)
            val barSamples = beatSamples * 4

            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                writeWavHeader(raf, totalSamples, sr)

                val buf = ByteArray(totalSamples * 2)
                for (i in 0 until totalSamples) {
                    val bar = (i / barSamples) % chords.size
                    val chord = chords[bar]
                    val t = i.toDouble() / sr

                    // 三个和弦音叠加，做柔和的包络
                    var v = 0.0
                    for ((k, freq) in chord.withIndex()) {
                        val amp = 0.16 / (k + 1)
                        v += amp * sin(2 * PI * freq * t) * env(t, barSamples.toDouble() / sr)
                        // 加一点高八度泛音，听起来不那么闷
                        v += amp * 0.3 * sin(2 * PI * freq * 2 * t) * env(t, barSamples.toDouble() / sr)
                    }

                    // 节奏层：底鼓 + 轻打点
                    val posInBar = i % barSamples
                    if (style == Style.ENERGY || style == Style.LIGHT) {
                        val kickLen = (sr * 0.12).toInt()
                        if (posInBar < kickLen) {
                            val kt = posInBar.toDouble() / sr
                            val f = 120 * exp(-kt * 28) + 45
                            v += 0.22 * sin(2 * PI * f * kt) * exp(-kt * 22)
                        }
                        val hatLen = (sr * 0.04).toInt()
                        if (posInBar % beatSamples < hatLen) {
                            val ht = (posInBar % beatSamples).toDouble() / sr
                            v += 0.05 * (Math.random() - 0.5) * exp(-ht * 90)
                        }
                    }

                    v = v.coerceIn(-1.0, 1.0)
                    val s = (v * 26000).toInt().coerceIn(-32767, 32767).toShort()
                    buf[i * 2] = (s.toInt() and 0xff).toByte()
                    buf[i * 2 + 1] = ((s.toInt() shr 8) and 0xff).toByte()
                }
                raf.write(buf)
            }

            DebugLog.i(context, "Music", "配乐合成 ${style.label} ${out.length() / 1024}KB")
            kotlin.Result.success(out)
        } catch (e: Exception) {
            DebugLog.e(context, "Music", "配乐合成失败", e)
            kotlin.Result.failure(e)
        }
    }

    /** 每个小节内的淡入淡出，避免和弦切换时爆音 */
    private fun env(t: Double, barDur: Double): Double {
        val x = (t % barDur) / barDur
        return when {
            x < 0.08 -> x / 0.08
            x > 0.88 -> (1 - x) / 0.12
            else -> 1.0
        }
    }

    private fun writeWavHeader(raf: RandomAccessFile, samples: Int, sr: Int) {
        val dataSize = samples * 2
        val byteRate = sr * 2
        raf.writeBytes("RIFF")
        raf.write(intLe(36 + dataSize))
        raf.writeBytes("WAVEfmt ")
        raf.write(intLe(16))
        raf.write(shortLe(1))
        raf.write(shortLe(1))
        raf.write(intLe(sr))
        raf.write(intLe(byteRate))
        raf.write(shortLe(2))
        raf.write(shortLe(16))
        raf.writeBytes("data")
        raf.write(intLe(dataSize))
    }

    private fun intLe(v: Int) = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte(),
    )

    private fun shortLe(v: Int) = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
    )
}
