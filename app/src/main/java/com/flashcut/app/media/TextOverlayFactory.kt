package com.flashcut.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings

/**
 * 字幕烧录。
 *
 * 之前字幕只导出成独立 .srt 文件，画面上什么都没有。
 * 这里把每条字幕渲染成透明底位图，用 OverlayEffect 叠加到对应片段上，
 * 字幕会真正合成进视频帧。
 *
 * 采用「每片段一条字幕」的模型：一条 TimelineClip 对应一句字幕，
 * 覆盖该片段时长，符合卡点/口播类剪辑的常见需要。
 */
@UnstableApi
object TextOverlayFactory {

    /**
     * 为单个片段生成字幕叠加效果。
     *
     * @param videoWidth 成片宽度
     * @param videoHeight 成片高度
     * @return 没有字幕时返回 null
     */
    fun forClip(
        text: String?,
        videoWidth: Int,
        videoHeight: Int,
    ): Effect? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null

        val bitmap = renderTextBitmap(t, videoWidth, videoHeight)
        if (bitmap.width <= 0 || bitmap.height <= 0) return null

        // 底部居中；backgroundFrameAnchor 的 y 取负值表示向下
        val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, -0.30f)
            .setOverlayFrameAnchor(0f, 0f)
            .build()

        return OverlayEffect(
            listOf(BitmapOverlay.createStaticBitmapOverlay(bitmap, settings))
        )
    }

    /** 把一条字幕画成透明底位图：白字 + 黑描边，任何画面上都能看清 */
    fun renderTextBitmap(text: String, videoWidth: Int, videoHeight: Int): Bitmap {
        val w = videoWidth.coerceAtLeast(360)
        val h = (videoHeight * 0.24f).toInt().coerceAtLeast(120)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val textSize = (videoHeight * 0.045f).coerceIn(28f, 100f)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(215, 0, 0, 0)
            this.textSize = textSize
            strokeWidth = textSize * 0.16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val maxWidth = w * 0.86f
        val lines = wrap(text, fill, maxWidth)
        val lineHeight = textSize * 1.32f
        val totalH = lineHeight * lines.size
        var y = (h - totalH) / 2f + textSize

        lines.forEach { line ->
            canvas.drawText(line, w / 2f, y, stroke)
            canvas.drawText(line, w / 2f, y, fill)
            y += lineHeight
        }
        return bmp
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (ch in text) {
            line.append(ch)
            if (paint.measureText(line.toString()) > maxWidth) {
                line.deleteCharAt(line.length - 1)
                if (line.isNotEmpty()) out += line.toString()
                line = StringBuilder().append(ch)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        return out.ifEmpty { listOf(text) }
    }
}
