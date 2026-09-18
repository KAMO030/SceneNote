package dev.scenenote.core.settings

import com.russhwolf.settings.Settings
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.PrivacyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.scenenote.shared.resources.*
import org.jetbrains.compose.resources.StringResource

/** 普通偏好（非敏感）。敏感数据（Key）只进 [SecureStore]。 */
class AppSettings(private val settings: Settings) {
    private val _privacy = MutableStateFlow(PrivacyMode.fromId(settings.getString(KEY_PRIVACY, "consent")))
    val privacy: StateFlow<PrivacyMode> = _privacy.asStateFlow()
    fun setPrivacy(mode: PrivacyMode) { settings.putString(KEY_PRIVACY, PrivacyMode.idOf(mode)); _privacy.value = mode }

    /** 主题色 id（core/designsystem `Accents`）；StateFlow 让设置页一点就全 App 换色。 */
    private val _accent = MutableStateFlow(settings.getString(KEY_ACCENT, "pink"))
    val accent: StateFlow<String> = _accent.asStateFlow()
    fun setAccent(id: String) { settings.putString(KEY_ACCENT, id); _accent.value = id }

    var myLang: String
        get() = settings.getString(KEY_MY_LANG, Lang.ZH_CN)
        set(v) = settings.putString(KEY_MY_LANG, v)
    /** 对方语言；[Lang.AUTO] = 会话里按定稿语种识别自动学（中 / 英 / 粤 / 日 / 韩），未识别前先按英语。 */
    var otherLang: String
        get() = settings.getString(KEY_OTHER_LANG, Lang.EN)
        set(v) = settings.putString(KEY_OTHER_LANG, v)

    /** 「我」的声纹（3D-Speaker embedding，逗号分隔浮点）：会话里注册后持久化，下次进对话页第一句就能靠声纹分人。 */
    var meVoice: FloatArray?
        get() = settings.getStringOrNull(KEY_ME_VOICE)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()?.takeIf { it.isNotEmpty() }
        set(v) = if (v == null) settings.remove(KEY_ME_VOICE) else settings.putString(KEY_ME_VOICE, v.joinToString(","))

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

    /**
     * 翻译只走端侧（设置 → 翻译模型 → 本机）：连着云端也不出网，密钥留在原处，换回任一云端档即恢复。
     * 判定落在 [dev.scenenote.translate.BailianMtTranslator.supports]，快路径 / 屏内字幕 / 慢路径都按它选路。
     */
    var mtLocalOnly: Boolean
        get() = settings.getBoolean(KEY_MT_LOCAL_ONLY, false)
        set(v) = settings.putBoolean(KEY_MT_LOCAL_ONLY, v)

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

    /** 系统字幕（S1）画中画：默认只显示译文；双语 = 原文小字在上（02 篇「屏内字幕」）。 */
    var captionBilingual: Boolean
        get() = settings.getBoolean(KEY_CAPTION_BILINGUAL, false)
        set(v) = settings.putBoolean(KEY_CAPTION_BILINGUAL, v)

    /** 一次性轻提示（会话页空态气泡等）：看过一次就不再打扰。 */
    fun hintSeen(key: String): Boolean = settings.getBoolean("hint.$key", false)
    fun markHintSeen(key: String) = settings.putBoolean("hint.$key", true)
    fun resetHints(vararg keys: String) = keys.forEach { settings.remove("hint.$it") }

    var onboardingDone: Boolean
        get() = settings.getBoolean(KEY_ONBOARDING, false)
        set(v) = settings.putBoolean(KEY_ONBOARDING, v)

    private companion object {
        const val KEY_PRIVACY = "privacy_default"
        const val KEY_ACCENT = "accent"
        const val KEY_MY_LANG = "my_lang"
        const val KEY_OTHER_LANG = "other_lang"
        const val KEY_ME_VOICE = "me_voice"
        const val KEY_MONTHLY_LIMIT = "monthly_limit"
        const val KEY_DEFAULT_PROVIDER = "default_provider"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_TTS_PREF = "tts_pref"
        const val KEY_MT_MODEL = "mt_model"
        const val KEY_MT_LOCAL_ONLY = "mt_local_only"
        const val KEY_LLM_MODEL = "llm_model"
        const val KEY_AUTO_POSTURE = "auto_posture"
        const val KEY_DIRECTION_AUTO = "direction_auto"
        const val KEY_POLITE_OPENER = "polite_opener"
        const val KEY_CAPTION_BILINGUAL = "caption_bilingual"
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
    val id: String, val name: StringResource, val host: String,
    val capabilities: Set<Capability>, val keyHint: StringResource, val docUrl: String, val region: String
) {
    enum class Capability { ASR, MT, LLM, TTS }
}

object Providers {
    val bailian = CloudProvider("bailian", Res.string.provider_bailian, "dashscope.aliyuncs.com",
        setOf(CloudProvider.Capability.ASR, CloudProvider.Capability.MT, CloudProvider.Capability.LLM, CloudProvider.Capability.TTS),
        keyHint = Res.string.provider_bailian_hint, docUrl = "https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key", region = "cn")
    val anthropic = CloudProvider("anthropic", Res.string.provider_anthropic, "api.anthropic.com",
        setOf(CloudProvider.Capability.LLM, CloudProvider.Capability.MT),
        keyHint = Res.string.provider_anthropic_hint, docUrl = "https://console.anthropic.com", region = "global")
    val openaiCompatible = CloudProvider("openai_compat", Res.string.provider_openai_compat, "",
        setOf(CloudProvider.Capability.LLM, CloudProvider.Capability.MT),
        keyHint = Res.string.provider_openai_compat_hint, docUrl = "", region = "custom")
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
