package com.flashcut.app.media

/** 导出时的完整配置：特效、字幕、配音、配乐都在这里决定。 */
data class ExportOptions(
    val effectPreset: EffectPreset = EffectPreset.ORIGINAL,
    /** 是否把片段字幕烧进画面 */
    val burnSubtitles: Boolean = true,
    /** 配音文案，空则不生成配音 */
    val narrationText: String = "",
    /** 配乐风格，null 表示不加配乐 */
    val musicStyle: MusicSynth.Style? = MusicSynth.Style.LIGHT,
    /** 配音相对原声的音量（0-1） */
    val narrationVolume: Float = 1.0f,
    /** 配乐音量（0-1），通常要压得比人声低 */
    val musicVolume: Float = 0.22f,
    /** 原声音量（0-1） */
    val originalVolume: Float = 0.55f,
    /** 转场类型 */
    val transition: TransitionFactory.Type = TransitionFactory.Type.NONE,
    /** 成片高度，宽度按 9:16 自动算 */
    val targetHeight: Int = 1280,
) {
    val targetWidth: Int get() = (targetHeight * 9 / 16)
}
