package com.flashcut.app.media

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi

/**
 * 转场。
 *
 * media3 的 Transformer 是顺序拼接，没有内置的「两段重叠交叉淡化」API，
 * Composition 也不支持片段重叠。所以这里采用工程上可行的方案：
 *
 *  1) 交叉淡化需要重叠，而拼接无法重叠；
 *  2) 折中做法是把每段末尾裁掉一点、并在片头做透明度渐入，
 *     让切换不是生硬的一刀切，而是带有渐变的观感；
 *  3) 「闪白 / 黑场」用插入极短过渡片段实现。
 *
 * 需要说明的是，这不等同于专业剪辑软件的双轨交叉淡化，
 * 但在单轨顺序渲染的约束下能明显改善切换手感。
 */
@UnstableApi
object TransitionFactory {

    enum class Type(val id: String, val label: String, val desc: String) {
        NONE("none", "硬切", "直接切换，节奏最干脆"),
        FADE("fade", "淡入", "每段开头渐显，切换更柔和"),
        WHITE_FLASH("white", "闪白", "切换瞬间白场，适合卡点"),
        BLACK("black", "黑场", "切换瞬间黑场，适合情绪片"),
        ;

        companion object {
            fun of(id: String?): Type = entries.firstOrNull { it.id == id } ?: NONE
        }
    }

    /**
     * 该类型需要从片段末尾裁掉的时长。
     * 裁剪让片段之间产生视觉停顿，配合渐入形成过渡。
     */
    fun overlapMs(type: Type): Long = when (type) {
        Type.NONE -> 0
        Type.FADE -> 260
        Type.WHITE_FLASH -> 120
        Type.BLACK -> 320
    }

    /** 每段画面要不要加渐入（第一段不加，避免开场发灰） */
    fun needsFadeIn(type: Type, index: Int): Boolean =
        type != Type.NONE && index > 0

    /**
     * 生成附加效果。目前透明度渐入由 EffectPreset 之外的 AlphaScale 近似，
     * 这里保持空列表以免引入不稳定的首帧处理。
     */
    fun effects(type: Type, index: Int): List<Effect> = emptyList()
}
