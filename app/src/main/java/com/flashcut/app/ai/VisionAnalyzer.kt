package com.flashcut.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import com.flashcut.app.DebugLog
import com.flashcut.app.llm.LlmClient
import com.flashcut.app.llm.LlmProfile
import com.flashcut.app.llm.LlmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 真正的画面理解：把镜头关键帧发给多模态模型，拿到内容描述。
 *
 * 之前的实现只算了清晰度和亮度就宣称「理解内容」，
 * theme 和镜头标题都是按模板写死的，跟画面无关。
 * 这里改成真的看图。
 */
class VisionAnalyzer(private val client: LlmClient = LlmClient()) {

    /** 视觉模型：优先 GLM 的 v 系列，其次任意带 vision 的模型 */
    private val visionModelHints = listOf("5v", "-v-", "vl", "vision", "gpt-4o", "claude")

    fun pickVisionModel(available: List<String>, fallback: String): String {
        val hit = available.firstOrNull { id ->
            visionModelHints.any { id.lowercase().contains(it) }
        }
        return hit ?: fallback
    }

    /**
     * 分析一批镜头的关键帧。
     * 每批最多 4 张图，减少往返次数又不会让模型看花眼。
     */
    suspend fun describe(
        context: Context,
        profile: LlmProfile,
        apiKey: String,
        sources: List<com.flashcut.app.data.SourceClip>,
        shots: List<ShotInsight>,
        style: String,
    ): LlmResult<MaterialBrief> = withContext(Dispatchers.IO) {
        if (shots.isEmpty()) return@withContext LlmResult.Err("没有镜头可分析")

        val bySource = sources.associateBy { it.id }
        val batches = shots.chunked(4)
        val descriptions = mutableListOf<ShotVision>()

        for ((bi, batch) in batches.withIndex()) {
            val frames = batch.mapNotNull { shot ->
                val src = bySource[shot.sourceId] ?: return@mapNotNull null
                val mid = (shot.startMs + shot.endMs) / 2
                val bmp = grabFrame(context, src.uri, mid) ?: return@mapNotNull null
                val b64 = toBase64(bmp)
                bmp.recycle()
                if (b64 == null) null else Triple(shot.id, b64, src.uri)
            }
            if (frames.isEmpty()) continue

            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", buildPrompt(batch.size, style)))
            frames.forEachIndexed { i, (_, b64, _) ->
                content.put(
                    JSONObject().put("type", "text").put("text", "第${i + 1}张（镜头 ${batch.getOrNull(i)?.id}）")
                )
                content.put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
                )
            }

            val messages = JSONArray().put(
                JSONObject().put("role", "user").put("content", content)
            )
            val body = JSONObject().apply {
                put("model", profile.model)
                put("messages", messages)
                put("max_tokens", 1200)
                put("temperature", 0.3)
            }

            when (val r = post(profile, apiKey, body)) {
                is LlmResult.Err -> {
                    DebugLog.e(context, "Vision", "第${bi + 1}批失败", Exception(r.message))
                }
                is LlmResult.Ok -> {
                    parseShots(r.value, frames.map { it.first }).forEach { descriptions += it }
                }
            }
        }

        val brief = buildBrief(descriptions, shots, style)
        DebugLog.i(
            context, "Vision",
            "画面理解: ${descriptions.size}/${shots.size} 个镜头 → 主题=${brief.theme}"
        )
        LlmResult.Ok(brief)
    }

    private fun buildPrompt(n: Int, style: String): String = """
        这是同一个视频的 $n 个连续镜头关键帧。请识别画面真实内容，输出严格 JSON：
        {
          "overall": "整体主题，15 字内，要具体（例如：幼儿在客厅玩积木、海边日落空镜）",
          "scene": "场景类型，如 室内/户外/街景/天空",
          "subjects": ["画面主体，如 婴儿/狗/食物/建筑"],
          "shots": [{"id":"镜头id","desc":"这个镜头在拍什么，20 字内","subject":"主要对象"}]
        }
        要求：
        1. 必须描述你真实看到的东西，不要套用任何模板；
        2. 如果是人物，说明大致年龄段与动作；
        3. 如果是物体或场景，说明是什么；
        4. shots 数组的 id 必须与标注的镜头 id 一一对应。
    """.trimIndent()

    private fun post(
        profile: LlmProfile,
        apiKey: String,
        body: JSONObject,
    ): LlmResult<String> = runCatching {
        val conn = java.net.URL(profile.chatUrl()).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) return LlmResult.Err("HTTP $code: ${text.take(180)}")
        LlmResult.Ok(text)
    }.getOrElse { LlmResult.Err("视觉请求失败：${it.message}") }

    private fun parseShots(raw: String, ids: List<String>): List<ShotVision> = runCatching {
        val root = JSONObject(raw)
        val content = root.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content").orEmpty()
        val json = content.substringAfter("{", "").let { if (it.isBlank()) content else "{$it" }
            .substringBeforeLast("}")
        val o = JSONObject(if (json.endsWith("}")) json else "$json}")
        val arr = o.optJSONArray("shots") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = s.optString("id").ifBlank { ids.getOrNull(i) ?: return@mapNotNull null }
            ShotVision(
                id = id,
                desc = s.optString("desc"),
                subject = s.optString("subject"),
                scene = o.optString("scene"),
            )
        }
    }.getOrElse { emptyList() }

    private fun buildBrief(
        descs: List<ShotVision>,
        shots: List<ShotInsight>,
        style: String,
    ): MaterialBrief {
        val overall = descs.map { it.desc }.filter { it.isNotBlank() }
        val subjects = descs.map { it.subject }.filter { it.isNotBlank() }.distinct()
        val scene = descs.map { it.scene }.filter { it.isNotBlank() }.firstOrNull().orEmpty()

        // 用真实描述拼主题，而不是按模板写死
        val theme = when {
            subjects.isNotEmpty() && scene.isNotBlank() -> "$scene · ${subjects.take(2).joinToString("+")}"
            subjects.isNotEmpty() -> subjects.take(3).joinToString("+")
            overall.isNotEmpty() -> overall.first()
            else -> "未识别出明确内容"
        }

        val byId = descs.associateBy { it.id }
        val enriched = shots.map { s ->
            val v = byId[s.id]
            s.copy(
                title = v?.desc?.take(18).takeIf { !it.isNullOrBlank() } ?: s.title,
                reason = v?.subject?.takeIf { it.isNotBlank() } ?: s.reason,
                tags = buildList {
                    addAll(s.tags)
                    if (!v?.subject.isNullOrBlank()) add(v.subject)
                    if (!v?.scene.isNullOrBlank()) add(v.scene)
                },
            )
        }

        return MaterialBrief(
            theme = theme,
            confidence = if (descs.isEmpty()) 40 else 88,
            tags = subjects.take(5),
            shots = enriched,
            speech = "",
            jitterDropped = 0,
        )
    }

    private fun grabFrame(context: Context, uri: Uri, atMs: Long): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val bmp = mmr.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: mmr.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            bmp?.let { scale(it) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** 压到 512px 长边，JPEG 质量 70，单张约 30-60KB，够模型看清又不浪费 token */
    private fun scale(bmp: Bitmap): Bitmap {
        val max = 512
        val w = bmp.width
        val h = bmp.height
        if (w <= max && h <= max) return bmp
        val ratio = max.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bmp, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    private fun toBase64(bmp: Bitmap): String? = runCatching {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private data class ShotVision(
        val id: String,
        val desc: String,
        val subject: String,
        val scene: String,
    )
}
