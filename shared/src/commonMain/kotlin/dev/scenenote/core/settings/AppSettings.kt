package dev.scenenote.core.settings

import com.russhwolf.settings.Settings
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.PrivacyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 普通偏好（非敏感）。敏感数据（Key）只进 [SecureStore]。 */
class AppSettings(private val settings: Settings) {
    private val _privacy = MutableStateFlow(PrivacyMode.fromId(settings.getString(KEY_PRIVACY, "consent")))
    val privacy: StateFlow<PrivacyMode> = _privacy.asStateFlow()
    fun setPrivacy(mode: PrivacyMode) { settings.putString(KEY_PRIVACY, PrivacyMode.idOf(mode)); _privacy.value = mode }

    var myLang: String
        get() = settings.getString(KEY_MY_LANG, Lang.ZH_CN)
        set(v) = settings.putString(KEY_MY_LANG, v)
    var otherLang: String
        get() = settings.getString(KEY_OTHER_LANG, Lang.EN)
        set(v) = settings.putString(KEY_OTHER_LANG, v)

    /** 本地消费闸门：每月估算费用上限（本币），0 = 不限制。 */
    var monthlyLimit: Double
        get() = settings.getDouble(KEY_MONTHLY_LIMIT, 0.0)
        set(v) = settings.putDouble(KEY_MONTHLY_LIMIT, v)

    var defaultProviderId: String?
        get() = settings.getStringOrNull(KEY_DEFAULT_PROVIDER)
        set(v) = if (v == null) settings.remove(KEY_DEFAULT_PROVIDER) else settings.putString(KEY_DEFAULT_PROVIDER, v)

    /** TTS 偏好：auto / system / local / off（tts.TtsPreference）。 */
    var ttsPreference: String
        get() = settings.getString(KEY_TTS_PREF, "auto")
        set(v) = settings.putString(KEY_TTS_PREF, v)

    /** 慢路径 LLM（百炼 OpenAI 兼容 chat；qwen-flash 便宜够用，可在设置里换）。 */
    var llmModel: String
        get() = settings.getString(KEY_LLM_MODEL, "qwen-flash")
        set(v) = settings.putString(KEY_LLM_MODEL, v)

    /** 快路径云端翻译模型（百炼 qwen-mt 系列）。 */
    var mtModel: String
        get() = settings.getString(KEY_MT_MODEL, "qwen-mt-flash")
        set(v) = settings.putString(KEY_MT_MODEL, v)

    /** M0 → M1 姿态自动进入（03 篇 §3.3；待决策：默认自动）。 */
    var autoPosture: Boolean
        get() = settings.getBoolean(KEY_AUTO_POSTURE, true)
        set(v) = settings.putBoolean(KEY_AUTO_POSTURE, v)

    /** 判向：auto（脚本 + 声纹）/ fixed（固定对方 → 我，退化单工）。待决策：默认自动。 */
    var directionAuto: Boolean
        get() = settings.getBoolean(KEY_DIRECTION_AUTO, true)
        set(v) = settings.putBoolean(KEY_DIRECTION_AUTO, v)

    /** 进入 M1 时自动播一句开场白（−6 dB，规格 §4.2）；默认关。 */
    var politeOpener: Boolean
        get() = settings.getBoolean(KEY_POLITE_OPENER, false)
        set(v) = settings.putBoolean(KEY_POLITE_OPENER, v)

    /** 一次性轻提示（会话页空态气泡等）：看过一次就不再打扰。 */
    fun hintSeen(key: String): Boolean = settings.getBoolean("hint.$key", false)
    fun markHintSeen(key: String) = settings.putBoolean("hint.$key", true)
    fun resetHints(vararg keys: String) = keys.forEach { settings.remove("hint.$it") }

    var onboardingDone: Boolean
        get() = settings.getBoolean(KEY_ONBOARDING, false)
        set(v) = settings.putBoolean(KEY_ONBOARDING, v)

    private companion object {
        const val KEY_PRIVACY = "privacy_default"
        const val KEY_MY_LANG = "my_lang"
        const val KEY_OTHER_LANG = "other_lang"
        const val KEY_MONTHLY_LIMIT = "monthly_limit"
        const val KEY_DEFAULT_PROVIDER = "default_provider"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_TTS_PREF = "tts_pref"
        const val KEY_MT_MODEL = "mt_model"
        const val KEY_LLM_MODEL = "llm_model"
        const val KEY_AUTO_POSTURE = "auto_posture"
        const val KEY_DIRECTION_AUTO = "direction_auto"
        const val KEY_POLITE_OPENER = "polite_opener"
    }
}

/** 平台安全存储：iOS Keychain（AfterFirstUnlockThisDeviceOnly）；Android Keystore AES-GCM 包裹后落 SharedPreferences。 */
interface SecureStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** 云端 provider 描述（不含任何 Key）。host 用于去向账本与 Egress 门面。 */
data class CloudProvider(
    val id: String, val name: String, val host: String,
    val capabilities: Set<Capability>, val keyHint: String, val docUrl: String, val region: String
) {
    enum class Capability { ASR, MT, LLM, TTS }
}

object Providers {
    val bailian = CloudProvider("bailian", "阿里云百炼", "dashscope.aliyuncs.com",
        setOf(CloudProvider.Capability.ASR, CloudProvider.Capability.MT, CloudProvider.Capability.LLM, CloudProvider.Capability.TTS),
        keyHint = "sk-…（百炼控制台 → 模型 → 设置 → API Key；北京地域）", docUrl = "https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key", region = "cn")
    val anthropic = CloudProvider("anthropic", "Anthropic", "api.anthropic.com",
        setOf(CloudProvider.Capability.LLM, CloudProvider.Capability.MT),
        keyHint = "sk-ant-…", docUrl = "https://console.anthropic.com", region = "global")
    val openaiCompatible = CloudProvider("openai_compat", "OpenAI 兼容端点", "",
        setOf(CloudProvider.Capability.LLM, CloudProvider.Capability.MT),
        keyHint = "sk-…（需同时填写 Base URL）", docUrl = "", region = "custom")
    val all = listOf(bailian, anthropic, openaiCompatible)
    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/** Key 钱包：只读写 SecureStore，从不日志、从不上传、从不内置。 */
class KeyWallet(private val store: SecureStore) {
    fun key(providerId: String): String? = store.get("key.$providerId")
    fun hasKey(providerId: String): Boolean = !key(providerId).isNullOrBlank()
    fun setKey(providerId: String, key: String) { if (key.isBlank()) store.remove("key.$providerId") else store.put("key.$providerId", key.trim()) }
    fun baseUrl(providerId: String): String? = store.get("base.$providerId")
    fun setBaseUrl(providerId: String, url: String) { if (url.isBlank()) store.remove("base.$providerId") else store.put("base.$providerId", url.trim()) }
    fun anyKey(): Boolean = Providers.all.any { hasKey(it.id) }
    fun masked(providerId: String): String? = key(providerId)?.let { k -> if (k.length <= 8) "••••" else k.take(4) + "••••" + k.takeLast(4) }
}
