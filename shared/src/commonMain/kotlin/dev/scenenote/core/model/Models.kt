package dev.scenenote.core.model

import dev.scenenote.audio.AudioMode
import kotlinx.serialization.Serializable

// ---------- core:model（全部不可变，与附录 A.1 对齐） ----------

@Serializable enum class Style { NEUTRAL, FORMAL, CASUAL, BUSINESS, ACADEMIC, SOCIAL, CUSTOM }

@Serializable enum class SceneKind {
    MEETING, DICTATION, DIALECT_FAMILY, LECTURE, INTERVIEW, SUPPORT, CUSTOM,
    LIVE_TALK /* 面对面对话（旗舰） */, LECTURE_LISTEN /* 讲座旁听 = M0 + 学术，v1.1 */, SCREEN_IN /* 屏内 */
}

@Serializable enum class SubScene { WECHAT, EMAIL, SOCIAL, QUICK_PHRASE /* M4 速译一句 */, VIDEO_SUBTITLE, COURSE_NOTES, DRAMA_SPEAKING }

@Serializable enum class RoutePolicy { LOCAL_ONLY, AUTO, CLOUD_FIRST }

@Serializable enum class Source { LOCAL, CLOUD, REMOTE /* P2P 远端流经听者本机 ASR */ }

/** 四档隐私只约束互联网出站；局域网出站另行处理（07 篇 §7.12）。 */
@Serializable sealed interface PrivacyMode {
    /** App 自身不向第三方 API / 局域网对端 / 模型直链发任何请求。 */
    @Serializable data object Locked : PrivacyMode
    /** 互联网默认不出站，按 segmentId / 会话临时授权。 */
    @Serializable data object LocalWithPerSegmentConsent : PrivacyMode
    /** 文本可上互联网，音频不可。 */
    @Serializable data object TextOnlyCloud : PrivacyMode
    /** 音频与文本都可上互联网。 */
    @Serializable data object AudioCloud : PrivacyMode

    val allowsInternetText: Boolean get() = this is TextOnlyCloud || this is AudioCloud
    val allowsInternetAudio: Boolean get() = this is AudioCloud
    val allowsLan: Boolean get() = this !is Locked

    companion object {
        fun fromId(id: String): PrivacyMode = when (id) {
            "locked" -> Locked
            "consent" -> LocalWithPerSegmentConsent
            "text" -> TextOnlyCloud
            "audio" -> AudioCloud
            else -> LocalWithPerSegmentConsent
        }
        fun idOf(mode: PrivacyMode): String = when (mode) {
            Locked -> "locked"
            LocalWithPerSegmentConsent -> "consent"
            TextOnlyCloud -> "text"
            AudioCloud -> "audio"
        }
    }
}

/** 语言标签统一用 BCP-47：zh-CN、yue-HK、zh-CN-sichuan、wuu-CN、nan-CN、en、ja、ko、de、fr、es、auto（仅 zh/yue/en 判别）。 */
object Lang {
    const val ZH_CN = "zh-CN"
    const val YUE_HK = "yue-HK"
    const val ZH_SICHUAN = "zh-CN-sichuan"
    const val WUU = "wuu-CN"
    const val NAN = "nan-CN"
    const val EN = "en"
    const val JA = "ja"
    const val KO = "ko"
    const val AUTO = "auto"

    /** MVP 端侧覆盖：基础包 zh-CN / en，方言包 yue-HK / zh-CN-sichuan（02 篇 §2.1）。 */
    val localCoverage: Set<String> = setOf(ZH_CN, EN, YUE_HK, ZH_SICHUAN)

    fun displayName(tag: String): String = when (tag) {
        ZH_CN -> "普通话"; YUE_HK -> "粤语"; ZH_SICHUAN -> "四川话"; WUU -> "上海话"; NAN -> "闽南语"
        EN -> "英语"; JA -> "日语"; KO -> "韩语"; AUTO -> "自动"; else -> tag
    }
}

@Serializable data class LangChip(val tag: String, val default: Boolean = false, val shownOnCard: Boolean = false)

@Serializable data class VadProfile(
    val thresholdx1000: Int = 500, val minSilenceMs: Int = 250, val minSpeechMs: Int = 250,
    val bgmReject: Boolean = false /* 媒体档：谱平坦度检测丢弃纯音乐段 */
) {
    companion object {
        /** 屏内媒体档预设；无独立 PipelineProfile.SCREEN_IN。 */
        fun media() = VadProfile(thresholdx1000 = 650, minSilenceMs = 600, minSpeechMs = 250, bgmReject = true)
    }
}

@Serializable data class EntryBinding(val kind: String /* control, actionButton, actionButtonLong, tile, tileScreenIn, shortcut, widget, mediaKey */, val id: String)
@Serializable data class FewShot(val before: String, val after: String)

@Serializable enum class ProfileTier { OFFLINE, HYBRID, ONLINE, S2S }

@Serializable enum class CaptionHostKind { IN_APP_PLAYER, PIP, OVERLAY, HEADSET_TTS, WATCH, NOTIFICATION }
@Serializable enum class DubbingPolicy { DUCK_REPLACE, MIX_NO_FOCUS, MUTE_ORIGINAL, BILINGUAL_MIX, OFF }
@Serializable enum class CaptionPosition { BOTTOM_CENTER, TOP_CENTER }
@Serializable data class CaptionStyle(
    val host: CaptionHostKind, val position: CaptionPosition = CaptionPosition.BOTTOM_CENTER,
    val fontSp: Int = 18 /* PIP 仅 14/18；22/28 仅 OVERLAY / IN_APP_PLAYER */, val bilingual: Boolean = true
)

@Serializable data class ScenePreset(
    val id: String, val kind: SceneKind, val subScene: SubScene? = null, val name: String, val version: Int,
    val route: RoutePolicy, val cloudProviderId: String? = null, val refineByDefault: Boolean = false,
    val langChips: List<LangChip>, val dialectPriority: List<String> = emptyList(),
    val style: Style, val customStyleInstruction: String? = null,
    val translationTargets: List<String> = emptyList(),
    val templateId: String, val privacy: PrivacyMode,
    val hotwordBucket: String, val fewShots: List<FewShot> = emptyList(),
    val vad: VadProfile = VadProfile(),
    val audioMode: AudioMode = AudioMode.DEFAULT,
    val entryBindings: List<EntryBinding> = emptyList(),
    // ---- 实时字段（录音场景为 null）----
    val liveModeId: String? = null,
    val screenModeId: String? = null,
    val profilePreference: ProfileTier = ProfileTier.HYBRID,
    val captionStyle: CaptionStyle? = null, val dubbing: DubbingPolicy = DubbingPolicy.OFF,
    /** 卡片副标题（UI 用，不进规格）。 */
    val tagline: String = "",
) {
    val isLive: Boolean get() = liveModeId != null
    val isScreenIn: Boolean get() = screenModeId != null
    val defaultLang: String get() = langChips.firstOrNull { it.default }?.tag ?: langChips.firstOrNull()?.tag ?: Lang.ZH_CN
}

@Serializable data class EngineInfo(val provider: String, val model: String, val version: String) {
    companion object { val NONE = EngineInfo("none", "none", "0") }
}
@Serializable data class Word(val startMs: Long, val endMs: Long, val text: String, val confidence: Float? = null)
@Serializable data class Edit(val type: String, val before: String, val after: String)
@Serializable data class Flag(val type: String /* uncertain, fact_risk, fact_check_failed, pii, term_replaced */, val span: String, val reason: String? = null)

/** 识别层不可变段；润色 / 翻译结果与修订放独立表。lang 为段级属性。 */
@Serializable data class Segment(
    val id: String, val startMs: Long, val endMs: Long, val lang: String,
    val rawText: String, val text: String, val confidence: Float? = null, val speaker: String? = null,
    val words: List<Word> = emptyList(), val isFinal: Boolean,
    val source: Source, val revision: Int, val engine: EngineInfo, val pendingCloud: Boolean = false
)

@Serializable data class Transcript(
    val id: String, val sceneId: String, val defaultLang: String,
    val engine: EngineInfo, val segments: List<Segment>
)

@Serializable data class PolishResult(
    val segmentId: String, val polished: String,
    val translations: Map<String, String> = emptyMap(), val edits: List<Edit> = emptyList(),
    val flags: List<Flag> = emptyList(), val providerUsed: String, val accepted: Boolean,
    val editDistanceRatio: Float, val factCheckPassed: Boolean
)

@Serializable data class PolishRevision(
    val segmentId: String, val rev: Int, val styleVer: String,
    val backend: String, val text: String, val userEdited: Boolean, val acceptedAt: Long?
)

@Serializable data class Correction(val wrong: String, val right: String, val bucket: String, val count: Int = 1)

@Serializable data class GlossaryEntry(
    val term: String, val bucket: String, val reading: String? = null,
    val translations: Map<String, String> = emptyMap(), val hits: Int = 0
)

@Serializable data class LedgerEntry(
    val ts: Long, val providerId: String, val host: String,
    val kind: String /* audio, text, model_asset */, val transport: String /* http, ws, sse, lan_ws, lan_http, ble */,
    val bytes: Long, val segmentId: String? = null, val estCost: Double? = null,
    val destinationClass: String = "internet" /* internet | lan */
)
