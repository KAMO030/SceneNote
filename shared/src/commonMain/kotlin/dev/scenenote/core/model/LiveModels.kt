package dev.scenenote.core.model

import kotlinx.serialization.Serializable

// ---------- 模式矩阵（附录 A.2；03 篇 §3.2） ----------
@Serializable enum class InputSource { MIC, SYSTEM_AUDIO, SCREEN_TEXT, MEDIA_FILE, REMOTE_STREAM }
@Serializable enum class OutputForm { TEXT, VOICE, BOTH }
@Serializable enum class OutputDevice { LOCAL, REMOTE_APP, REMOTE_WEB, MY_WATCH, REMOTE_BROADCAST }
@Serializable enum class Interaction { CONVERSATION, SIMPLEX_IN, SIMPLEX_OUT }
@Serializable enum class VoiceRoute { MY_HEADSET, SPLIT_LR, SPEAKER_YELLOW, NONE }

@Serializable data class ModeSpec(
    val id: String /* "M0".."M12" */, val name: String,
    val input: InputSource = InputSource.MIC,
    val form: OutputForm, val device: OutputDevice, val interaction: Interaction,
    val voiceRoute: VoiceRoute, val showFacingScreen: Boolean, val autoDirection: Boolean,
    val requiresAec: Boolean, val requiresP2p: Boolean, val whisperGainDb: Float = -20f,
    val stage: String /* P0|P1|P2|YELLOW|RESERVED */, val summary: String = ""
)

/** 内置 ModeSpec 表（三份文档以此为唯一真相源；M2 保留不分配）。 */
object ModeSpecs {
    val all: List<ModeSpec> = listOf(
        ModeSpec("M0", "仅听", form = OutputForm.VOICE, device = OutputDevice.LOCAL, interaction = Interaction.SIMPLEX_IN,
            voiceRoute = VoiceRoute.MY_HEADSET, showFacingScreen = false, autoDirection = false, requiresAec = false, requiresP2p = false,
            stage = "P0", summary = "只我戴耳机，手机在口袋；对方说，我耳机听译文"),
        ModeSpec("M1", "耳听·面屏", form = OutputForm.BOTH, device = OutputDevice.LOCAL, interaction = Interaction.CONVERSATION,
            voiceRoute = VoiceRoute.MY_HEADSET, showFacingScreen = true, autoDirection = true, requiresAec = false, requiresP2p = false,
            stage = "P0", summary = "我耳机私听；对方看我半屏大字与礼貌卡"),
        ModeSpec("M2", "保留", form = OutputForm.TEXT, device = OutputDevice.LOCAL, interaction = Interaction.SIMPLEX_IN,
            voiceRoute = VoiceRoute.NONE, showFacingScreen = false, autoDirection = false, requiresAec = false, requiresP2p = false,
            stage = "RESERVED", summary = "编号保留，不分配"),
        ModeSpec("M3", "双屏对话", form = OutputForm.TEXT, device = OutputDevice.LOCAL, interaction = Interaction.CONVERSATION,
            voiceRoute = VoiceRoute.NONE, showFacingScreen = true, autoDirection = true, requiresAec = false, requiresP2p = false,
            stage = "P0", summary = "无耳机；上下半屏各显各语言，从 M1 摘耳机自动降级"),
        ModeSpec("M4", "速译卡片", form = OutputForm.TEXT, device = OutputDevice.LOCAL, interaction = Interaction.SIMPLEX_OUT,
            voiceRoute = VoiceRoute.NONE, showFacingScreen = true, autoDirection = false, requiresAec = false, requiresP2p = false,
            stage = "P0", summary = "说一句，大字朝向对方"),
        ModeSpec("M5", "分耳对话", form = OutputForm.VOICE, device = OutputDevice.LOCAL, interaction = Interaction.CONVERSATION,
            voiceRoute = VoiceRoute.SPLIT_LR, showFacingScreen = false, autoDirection = true, requiresAec = false, requiresP2p = false,
            stage = "P1", summary = "一副 TWS 拆两只，左右声道分语种"),
        ModeSpec("M6", "外放速译", form = OutputForm.VOICE, device = OutputDevice.LOCAL, interaction = Interaction.SIMPLEX_OUT,
            voiceRoute = VoiceRoute.SPEAKER_YELLOW, showFacingScreen = false, autoDirection = false, requiresAec = true, requiresP2p = false,
            stage = "YELLOW", summary = "近场限幅外放，显式降级"),
        ModeSpec("M7", "卡片+朗读", form = OutputForm.BOTH, device = OutputDevice.LOCAL, interaction = Interaction.SIMPLEX_OUT,
            voiceRoute = VoiceRoute.SPEAKER_YELLOW, showFacingScreen = true, autoDirection = false, requiresAec = true, requiresP2p = false,
            stage = "YELLOW", summary = "M4 + 外放"),
        ModeSpec("M8", "双机私聊", input = InputSource.REMOTE_STREAM, form = OutputForm.BOTH, device = OutputDevice.REMOTE_APP, interaction = Interaction.CONVERSATION,
            voiceRoute = VoiceRoute.MY_HEADSET, showFacingScreen = false, autoDirection = true, requiresAec = false, requiresP2p = true,
            stage = "P1", summary = "双方装 App，只互发音频流，各自本地 ASR→翻译→TTS"),
        ModeSpec("M9", "双机字幕", input = InputSource.REMOTE_STREAM, form = OutputForm.TEXT, device = OutputDevice.REMOTE_APP, interaction = Interaction.CONVERSATION,
            voiceRoute = VoiceRoute.NONE, showFacingScreen = false, autoDirection = true, requiresAec = false, requiresP2p = true,
            stage = "P1", summary = "同 M8，听者关 TTS 看屏"),
        ModeSpec("M10", "多人同传", input = InputSource.REMOTE_STREAM, form = OutputForm.BOTH, device = OutputDevice.REMOTE_APP, interaction = Interaction.SIMPLEX_OUT,
            voiceRoute = VoiceRoute.MY_HEADSET, showFacingScreen = false, autoDirection = false, requiresAec = false, requiresP2p = true,
            stage = "P2", summary = "星型房间，说话者只发音频，N 个听者各译成自己的语言"),
        ModeSpec("M11", "讲座字幕", input = InputSource.REMOTE_STREAM, form = OutputForm.TEXT, device = OutputDevice.REMOTE_WEB, interaction = Interaction.SIMPLEX_OUT,
            voiceRoute = VoiceRoute.NONE, showFacingScreen = false, autoDirection = false, requiresAec = false, requiresP2p = true,
            stage = "P2", summary = "一人讲多人看；App 听者 + 网页听者混合"),
        ModeSpec("M12", "抬腕字幕", form = OutputForm.TEXT, device = OutputDevice.MY_WATCH, interaction = Interaction.SIMPLEX_IN,
            voiceRoute = VoiceRoute.NONE, showFacingScreen = false, autoDirection = false, requiresAec = false, requiresP2p = false,
            stage = "P1", summary = "手表显示最近译文与方向，随 M0/M1 自动开启"),
    )
    private val byId = all.associateBy { it.id }
    fun byId(id: String): ModeSpec = byId[id] ?: error("unknown mode $id")
    val mvp: List<ModeSpec> get() = all.filter { it.stage == "P0" }
}

@Serializable enum class Speaker { ME, OTHER, REMOTE, UNKNOWN }
@Serializable enum class SegmentState { PARTIAL, STABLE_PREFIX, FINAL }
@Serializable enum class PlaybackStatus { QUEUED, PLAYING, DONE, SKIPPED, SHOWN_ON_SCREEN, MERGED }
@Serializable enum class UttSource { LOCAL_MIC, REMOTE_AUDIO, REMOTE_HINT, SYSTEM_AUDIO, MEDIA_FILE }

@Serializable data class LiveSegment(
    val id: String, val utteranceId: String, val state: SegmentState, val text: String,
    val startMs: Long, val endMs: Long?, val asrEngine: EngineInfo, val confidence: Float? = null, val streamId: Int = 0
)
@Serializable data class Translation(val text: String, val engine: String, val provisional: Boolean, val latencyMs: Int, val targetLang: String)
@Serializable data class Utterance(
    val id: String, val speaker: Speaker, val lang: String, val segments: List<LiveSegment>, val raw: String,
    val fastTranslation: Translation?, val polished: String? = null,
    val finalTranslation: Map<String, Translation> = emptyMap(),
    val ttsPlayback: PlaybackStatus = PlaybackStatus.QUEUED, val source: UttSource = UttSource.LOCAL_MIC,
    val captureTsMs: Long, val glossaryHits: List<String> = emptyList()
)
@Serializable data class PipelineProfile(
    val tier: ProfileTier, val asr: String, val mt: String, val tts: String,
    val eagerTranslate: Boolean, val timeoutsMs: Map<String, Int> /* mt=1500, ttsFirst=800, asrFirst=1200 */
) {
    companion object {
        val OFFLINE = PipelineProfile(ProfileTier.OFFLINE, "sherpa:zipformer-zh-en", "system", "system", eagerTranslate = false,
            timeoutsMs = mapOf("mt" to 3000, "ttsFirst" to 800, "asrFirst" to 1200))
        val HYBRID = PipelineProfile(ProfileTier.HYBRID, "sherpa:zipformer-zh-en", "cloud:fast", "system", eagerTranslate = true,
            timeoutsMs = mapOf("mt" to 1500, "ttsFirst" to 800, "asrFirst" to 1200))
    }
}
@Serializable data class Conversation(
    val id: String, val modeId: String, val myLang: String, val otherLang: String, val sceneId: String,
    val style: Style, val profile: PipelineProfile, val utterances: List<Utterance>, val summary: String? = null,
    val myAudioPath: String? = null /* 对方音频不落盘 */
)

// ---------- Stage 链事件 ----------
sealed interface FastPathEvent {
    data class Partial(val segment: LiveSegment) : FastPathEvent
    data class StablePrefix(val segment: LiveSegment) : FastPathEvent
    data class Final(val utterance: Utterance) : FastPathEvent
    data class Direction(val speaker: Speaker, val confidence: Float, val tentative: Boolean) : FastPathEvent
    data class TranslationDelta(val utteranceId: String, val delta: String, val provisional: Boolean) : FastPathEvent
    class TtsChunk(val utteranceId: String, val pcm: ShortArray, val last: Boolean) : FastPathEvent
    data class Health(val stage: String, val state: StageHealth) : FastPathEvent
    data class Flushed(val reason: String /* other_interrupt | me_speaking | earbud_lost */, val movedToScreen: List<String>) : FastPathEvent
}
enum class StageHealth { OK, SLOW, FALLBACK, PROBING, RECOVERED }

// ---------- 实时会话状态机 ----------
sealed interface LiveState {
    data object Idle : LiveState
    data object Arming : LiveState
    data class Live(val play: PlayState, val dir: DirState) : LiveState
    data class Paused(val reason: String /* call | siri | earbud_lost */) : LiveState
    data object NeedForeground : LiveState
    data object Degraded : LiveState
    data object Ending : LiveState
}
enum class PlayState { LISTENING, TRANSLATING, SPEAKING, DUCKED, FLUSHED }
enum class DirState { UNDETERMINED, OTHER_SPEAKING, ME_SPEAKING }
