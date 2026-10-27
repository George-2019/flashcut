package com.flashcut.app.media

import androidx.media3.common.Effect
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.SingleColorLut

/**
 * 成片风格预设。
 *
 * 之前导出只做缩放（Effects(emptyList())），所谓「自动应用特效」是空的。
 * 这里把每种风格映射成真实的 GlEffect 链，导出时逐帧生效。
 */
enum class EffectPreset(
    val id: String,
    val label: String,
    val desc: String,
) {
    ORIGINAL("original", "原片", "不做调色，保持原始色彩"),
    WARM("warm", "暖阳", "提高色温与饱和，适合旅行/生活"),
    COOL("cool", "青蓝", "冷色调，适合城市/科技感"),
    VIVID("vivid", "浓郁", "高对比高饱和，适合燃向混剪"),
    FILM("film", "胶片", "低饱和 + 轻对比，复古质感"),
    MONO("mono", "黑白", "去色，强对比，适合情绪片"),
    SOFT("soft", "柔光", "提亮 + 降对比，适合人像/萌娃"),
    ;

    /**
     * 生成这条预设的真实渲染效果链。
     * 顺序有讲究：先调色，再调对比度，最后套滤镜矩阵。
     */
    fun buildEffects(): List<Effect> = when (this) {
        ORIGINAL -> emptyList()

        WARM -> listOf(
            HslAdjustment.Builder()
                .adjustSaturation(18f)
                .adjustHue(4f)
                .adjustLightness(6f)
                .build(),
            Contrast(-0.04f),
        )

        COOL -> listOf(
            RgbAdjustment.Builder()
                .setRedScale(0.92f)
                .setGreenScale(0.98f)
                .setBlueScale(1.10f)
                .build(),
            HslAdjustment.Builder().adjustSaturation(6f).build(),
        )

        VIVID -> listOf(
            HslAdjustment.Builder()
                .adjustSaturation(34f)
                .build(),
            Contrast(0.16f),
        )

        FILM -> listOf(
            HslAdjustment.Builder()
                .adjustSaturation(-22f)
                .adjustLightness(-2f)
                .build(),
            Contrast(0.07f),
        )

        MONO -> listOf(
            RgbFilter.createGrayscaleFilter(),
            Contrast(0.22f),
        )

        SOFT -> listOf(
            HslAdjustment.Builder()
                .adjustLightness(9f)
                .adjustSaturation(-6f)
                .build(),
            Contrast(-0.10f),
        )
    }

    companion object {
        fun of(id: String?): EffectPreset =
            entries.firstOrNull { it.id == id } ?: ORIGINAL
    }
}
