package com.flashcut.app.llm

import android.content.Context
import com.flashcut.app.BuildConfig
import androidx.core.content.edit

/** 一套可切换的大模型接口配置（OpenAI 兼容协议）。 */
data class LlmProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val timeoutSec: Int = 60,
    val supportsJsonMode: Boolean = true,
    val isLocal: Boolean = false,
) {
    /** 去掉末尾斜杠，拼出 chat completions 地址。兼容用户直接填完整路径的情况。 */
    fun chatUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    fun modelsUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions") + "/models"
            base.endsWith("/v1") -> "$base/models"
            else -> "$base/v1/models"
        }
    }
}

object LlmPresets {
    val all: List<LlmProfile> = listOf(
        LlmProfile("deepseek", "DeepSeek 官方", "https://api.deepseek.com/v1", "deepseek-chat"),
        LlmProfile("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        LlmProfile("kimi", "Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-32k"),
        LlmProfile("glm", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus"),
        LlmProfile("doubao", "豆包", "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k"),
        LlmProfile("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        LlmProfile("ollama", "本地 Ollama", "http://192.168.1.20:11434/v1", "qwen2.5:7b", isLocal = true),
        LlmProfile(
            "workbuddy",
            "WorkBuddy（本机服务）",
            BuildConfig.WORKBUDDY_BASE_URL,
            "deepseek-v4.1-flash",
            timeoutSec = 30,
            isLocal = true,
        ),
        LlmProfile("custom", "自定义接口", "", ""),
    )

    fun byId(id: String): LlmProfile = all.firstOrNull { it.id == id } ?: all.first()
}

/**
 * 配置持久化。API Key 使用 Android Keystore 加密后再写入 SharedPreferences。
 * 注意：MVP 用软件加密占位，正式版必须换成 Keystore + AES/GCM（见 docs/大模型配置方案.md）。
 */
class LlmConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("llm_config", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("llm_secret", Context.MODE_PRIVATE)

    /**
     * 首次启动时写入 WorkBuddy 预设：把当前服务的地址与 Key 作为默认配置。
     * 只在用户从未配置过时执行，之后用户手动改的配置不会被覆盖。
     */
    fun seedWorkBuddyIfEmpty(apiKey: String?) {
        if (prefs.contains("active_id")) return
        val preset = LlmPresets.byId("workbuddy")
        save(preset, apiKey)
        // 记录候选地址，界面上可提示用户切换
        prefs.edit { putString("wb_candidates", BuildConfig.WORKBUDDY_CANDIDATES) }
    }

    /** 缓存某服务商拉取过的模型列表，下次打开配置页直接可选 */
    fun saveModels(profileId: String, models: List<String>) {
        prefs.edit { putString("models_$profileId", models.joinToString(",")) }
    }

    fun loadModels(profileId: String): List<String> =
        prefs.getString("models_$profileId", null)
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    fun workbuddyCandidates(): List<String> =
        prefs.getString("wb_candidates", BuildConfig.WORKBUDDY_CANDIDATES)
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    fun loadActive(): LlmProfile {
        val id = prefs.getString("active_id", LlmPresets.all.first().id) ?: LlmPresets.all.first().id
        val preset = LlmPresets.byId(id)
        return LlmProfile(
            id = preset.id,
            name = prefs.getString("name", preset.name) ?: preset.name,
            baseUrl = prefs.getString("base_url", preset.baseUrl) ?: preset.baseUrl,
            model = prefs.getString("model", preset.model) ?: preset.model,
            temperature = prefs.getFloat("temperature", 0.7f),
            maxTokens = prefs.getInt("max_tokens", 4096),
            timeoutSec = prefs.getInt("timeout", 60),
            supportsJsonMode = prefs.getBoolean("json_mode", true),
            isLocal = preset.isLocal,
        )
    }

    fun save(profile: LlmProfile, apiKey: String?) {
        prefs.edit {
            putString("active_id", profile.id)
            putString("name", profile.name)
            putString("base_url", profile.baseUrl)
            putString("model", profile.model)
            putFloat("temperature", profile.temperature)
            putInt("max_tokens", profile.maxTokens)
            putInt("timeout", profile.timeoutSec)
            putBoolean("json_mode", profile.supportsJsonMode)
        }
        if (!apiKey.isNullOrBlank()) {
            secrets.edit { putString(profile.id, SecretBox.obscure(apiKey)) }
        }
    }

    fun apiKey(profileId: String): String? =
        secrets.getString(profileId, null)?.let { SecretBox.reveal(it) }

    fun hasKey(profileId: String): Boolean = !apiKey(profileId).isNullOrBlank()
}

/** 占位加密：正式版替换为 Keystore AES/GCM，接口保持不变。 */
private object SecretBox {
    private const val SALT = 0x5A
    fun obscure(raw: String): String =
        raw.toByteArray().map { (it.toInt() xor SALT).toByte() }.toByteArray().let {
            android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
        }

    fun reveal(enc: String): String? = runCatching {
        android.util.Base64.decode(enc, android.util.Base64.NO_WRAP)
            .map { (it.toInt() xor SALT).toByte() }.toByteArray().toString(Charsets.UTF_8)
    }.getOrNull()
}
