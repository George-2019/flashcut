package com.flashcut.app.llm

/**
 * 推理模型的特殊处理。
 *
 * 实测发现：hy4-preview 这类模型会先输出 reasoning_content（思考过程），
 * 思考本身就吃掉大量 token；如果 max_tokens 不够，正文 content 会直接为空，
 * 表现为「脚本解析失败：Unterminated string」这种莫名其妙的中断。
 *
 * 对策：识别推理模型 → 放大 token 预算 → 截断时给出人能看懂的提示。
 */
object ReasoningModel {

    /** 已知的推理型模型名特征（小写匹配） */
    private val HINTS = listOf(
        "hy4",          // WorkBuddy 的推理模型
        "reason", "reasoning", "think", "thinking",
        "o1", "o3", "o4",
        "r1", "qwq", "deepseek-r",
    )

    /** 非推理的大模型常带这些后缀，避免误判 */
    private val NOT_REASONING = listOf(
        "deepseek-v4.1-flash", "deepseek-v4-pro", "deepseek-v4-flash",
        "deepseek-v3", "glm", "kimi", "qwen-max", "qwen-plus", "qwen-turbo",
        "doubao", "claude", "gpt-4o", "gpt-4.1",
    )

    fun isReasoning(model: String): Boolean {
        val m = model.lowercase()
        if (NOT_REASONING.any { m.contains(it) }) return false
        return HINTS.any { m.contains(it) }
    }

    /**
     * 推理模型需要额外预算：思考部分通常 1-3k token，再加正文。
     * 给不足就会得到空 content。
     */
    fun tokenBudget(model: String, base: Int): Int {
        if (!isReasoning(model)) return base
        return maxOf(base, 4096) + 4096
    }

    /**
     * 推理模型的超时预算。
     * 实测 hy4-preview 单次请求 86 秒（思考 3772 token），60 秒必然超时。
     */
    fun timeoutSec(model: String, base: Int): Int {
        if (!isReasoning(model)) return base
        return maxOf(base, 180)
    }

    /** 截断时的可读提示 */
    fun truncatedHint(model: String, reasoningTokens: Int): String = buildString {
        append("模型「$model」输出被截断")
        if (isReasoning(model) && reasoningTokens > 0) {
            append("：它先花 $reasoningTokens 个 token 思考，正文还没开始就没额度了。")
            append("建议换非推理模型（如 deepseek-v4.1-flash），或在配置里把「最大输出 token」调到 8192 以上。")
        } else {
            append("。请在配置里调大「最大输出 token」。")
        }
    }
}
