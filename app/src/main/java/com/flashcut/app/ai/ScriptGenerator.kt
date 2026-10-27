package com.flashcut.app.ai

import com.flashcut.app.llm.LlmClient
import com.flashcut.app.llm.LlmProfile
import com.flashcut.app.llm.LlmResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把本地分析结论 + 用户意图交给大模型，拿回可编辑的剪辑脚本。
 * 大模型看不到原片，只看到镜头摘要，所以又快又省 token。
 */
class ScriptGenerator(private val client: LlmClient = LlmClient()) {

    private val styleName = mapOf(
        "vlog" to "旅行/生活 Vlog，舒缓叙事，高光混剪",
        "beat" to "情绪卡点/燃向，音乐驱动，重特效",
        "talk" to "口播/播客切片，删停顿，强字幕",
        "shop" to "好物/带货，快节奏，卖点字幕",
    )

    suspend fun generate(
        profile: LlmProfile,
        apiKey: String,
        brief: MaterialBrief,
        style: String,
        targetSec: Int,
        hint: String = "",
    ): LlmResult<EditScript> {
        // 没有镜头可剪辑，直接走本地兜底，避免无意义请求
        if (brief.shots.isEmpty()) {
            return LlmResult.Err("素材没有可用镜头（可能是视频过短或无法解码）")
        }

        val system = """
            你是资深短视频剪辑师。根据给定的镜头清单和意图，输出严格 JSON（不要 markdown 围栏），格式：
            {
              "title": "成片标题",
              "narration": "完整解说词，一段自然口语，不超过 90 字",
              "music": "音乐风格描述",
              "effect": "特效与转场说明",
              "cuts": [
                {"shotId":"镜头id", "in": 相对镜头的入点秒, "out": 出点秒, "caption": "这句字幕"}
              ]
            }
            硬性要求：
            1. cuts 里的 shotId 必须来自输入清单，不能编造；
            2. 每个 cut 的 out > in，且 out <= 该镜头的 duration；
            3. 所有 cut 时长加起来尽量接近目标秒数，误差不超过 15%；
            4. 按情绪曲线排序，优先选 score 高的镜头；
            5. caption 每条不超过 18 个汉字，口语化。
        """.trimIndent()

        val shots = JSONArray()
        brief.shots.forEach { s ->
            shots.put(
                JSONObject().apply {
                    put("shotId", s.id)
                    put("title", s.title)
                    put("duration", String.format("%.1f", s.durationMs / 1000f))
                    put("score", s.score)
                    put("reason", s.reason)
                }
            )
        }
        val user = buildString {
            appendLine("剪辑风格：${styleName[style] ?: styleName["vlog"]}")
            appendLine("目标时长：${targetSec} 秒")
            appendLine("素材主题：${brief.theme}")
            if (brief.speech.isNotBlank()) appendLine("语音要点：${brief.speech}")
            if (hint.isNotBlank()) appendLine("用户补充要求：$hint")
            appendLine("镜头清单：")
            append(shots.toString())
        }

        return when (val r = client.generateRaw(profile, apiKey, system, user)) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> parse(r.value, brief, style, targetSec, hint)
        }
    }

    /** 宽松匹配镜头：精确 → 去空格/大小写 → 数字序号 → 位置顺序 */
    private fun matchShot(raw: String, byId: Map<String, ShotInsight>, all: List<ShotInsight>, index: Int): ShotInsight? {
        if (raw.isBlank()) return all.getOrNull(index)

        byId[raw]?.let { return it }
        val norm = raw.trim().lowercase().replace(" ", "")
        byId[norm]?.let { return it }
        all.firstOrNull { it.id.lowercase() == norm }?.let { return it }

        // 纯数字：可能是 1-based 或 0-based 序号
        norm.toIntOrNull()?.let { n ->
            all.firstOrNull { it.id == "s$n" }?.let { return it }
            all.getOrNull(n)?.let { return it }
            all.getOrNull(n - 1)?.let { return it }
        }

        // 形如 s1 / shot1 / 镜头1
        Regex("(\\d+)").find(norm)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { n ->
            all.firstOrNull { it.id == "s$n" }?.let { return it }
            all.getOrNull(n - 1)?.let { return it }
        }

        // 实在匹配不上，按位置兜底，保证流程不中断
        return all.getOrNull(index)
    }

    private fun firstDouble(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (!o.has(k)) continue
            val v = o.opt(k)
            when (v) {
                is Number -> return v.toDouble()
                is String -> v.trim().trimEnd('s').toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun parse(
        raw: String,
        brief: MaterialBrief,
        style: String,
        targetSec: Int,
        hint: String,
    ): LlmResult<EditScript> = runCatching {
        val o = JSONObject(raw)
        val byId = brief.shots.associateBy { it.id }
        val rawCuts = (0 until (o.optJSONArray("cuts")?.length() ?: 0)).mapNotNull { i ->
            o.optJSONArray("cuts")?.optJSONObject(i)?.let {
                it.optString("shotId").ifBlank { it.optString("id") }
            }
        }
        val cuts = mutableListOf<ScriptCut>()
        val arr = o.optJSONArray("cuts") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            // 容错：模型可能返回 shotId / id / shot / index 等不同字段名
            val rawId = sequenceOf("shotId", "id", "shot", "shot_id", "index")
                .map { c.optString(it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()

            val shot = matchShot(rawId, byId, brief.shots, i) ?: continue
            val inSec = firstDouble(c, "in", "start", "from", "inSec") ?: 0.0
            val outSec = firstDouble(c, "out", "end", "to", "outSec") ?: (shot.durationMs / 1000.0)
            val durSec = shot.durationMs / 1000f
            val realIn = inSec.toFloat().coerceIn(0f, (durSec - 0.5f).coerceAtLeast(0f))
            val realOut = outSec.toFloat().coerceIn(realIn + 0.5f, durSec)
            cuts += ScriptCut(
                shotId = shot.id,
                sourceId = shot.sourceId,
                inMs = (realIn * 1000).toLong(),
                outMs = (realOut * 1000).toLong(),
                caption = sequenceOf("caption", "text", "subtitle", "sub")
                    .map { c.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty(),
                speed = firstDouble(c, "speed", "rate")?.toFloat()?.coerceIn(0.5f, 2f) ?: 1f,
            )
        }
        if (cuts.isEmpty()) {
            val got = o.optJSONArray("cuts")?.length() ?: 0
            val sample = brief.shots.take(3).joinToString(",") { it.id }
            return LlmResult.Err(
                "模型返回 $got 个镜头但无法匹配。我方镜头编号如 $sample；" +
                    "模型给的编号：${rawCuts.firstOrNull().orEmpty()}"
            )
        }
        val script = EditScript(
            title = o.optString("title", "闪剪成片"),
            style = style,
            targetDurationSec = targetSec,
            narration = o.optString("narration"),
            music = o.optString("music", "轻快电子"),
            effectNote = o.optString("effect", "叠化衔接 + 暖色 LUT"),
            captions = cuts.map { it.caption },
            cuts = cuts,
        )
        LlmResult.Ok(script)
    }.getOrElse { e ->
        LlmResult.Err("脚本解析失败：${e.message}")
    }

    /** 大模型不可用时的兜底：按分数取前 N 个镜头拼到目标时长。 */
    fun fallback(brief: MaterialBrief, style: String, targetSec: Int): EditScript {
        val budget = targetSec * 1000L
        val cuts = mutableListOf<ScriptCut>()
        var used = 0L
        for (s in brief.shots) {
            if (used >= budget) break
            val take = minOf(s.durationMs, budget - used, 6000L)
            if (take < 800) continue
            cuts += ScriptCut(
                shotId = s.id,
                sourceId = s.sourceId,
                inMs = 0,
                outMs = take,
                caption = "",
            )
            used += take
        }
        if (cuts.isEmpty() && brief.shots.isNotEmpty()) {
            val s = brief.shots.first()
            cuts += ScriptCut(s.id, s.sourceId, 0, s.durationMs.coerceAtMost(budget))
        }
        return EditScript(
            title = "本地规则成片",
            style = style,
            targetDurationSec = targetSec,
            narration = "",
            music = "轻快电子",
            effectNote = "叠化衔接",
            cuts = cuts,
        )
    }
}
