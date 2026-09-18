package dev.scenenote.core.model

import dev.scenenote.audio.AudioMode
import dev.scenenote.shared.resources.*
import org.jetbrains.compose.resources.StringResource

/** 内置场景卡（精简 MVP：面对面对话 / 仅听 / 速译一句 / 会议 / 屏内文件字幕）。01 篇 §1.3 的参数表子集。 */
object Scenes {
    val liveTalk = ScenePreset(
        id = "live_talk", kind = SceneKind.LIVE_TALK, name = "面对面对话", version = 1,
        route = RoutePolicy.AUTO, langChips = listOf(LangChip(Lang.ZH_CN, default = true, shownOnCard = true), LangChip(Lang.EN, shownOnCard = true)),
        style = Style.CASUAL, translationTargets = listOf(Lang.EN), templateId = "conversation_card",
        privacy = PrivacyMode.TextOnlyCloud, hotwordBucket = "travel", audioMode = AudioMode.LIVE_HEADSET,
        liveModeId = "M1", profilePreference = ProfileTier.HYBRID,
        tagline = "戴上耳机就能听；掏出手机对方看半屏",
    )
    val listenOnly = ScenePreset(
        id = "listen", kind = SceneKind.LIVE_TALK, name = "仅听", version = 1,
        route = RoutePolicy.AUTO, langChips = listOf(LangChip(Lang.EN, default = true, shownOnCard = true), LangChip(Lang.YUE_HK, shownOnCard = true), LangChip(Lang.ZH_SICHUAN)),
        style = Style.NEUTRAL, translationTargets = listOf(Lang.ZH_CN), templateId = "conversation_card",
        privacy = PrivacyMode.TextOnlyCloud, hotwordBucket = "general", audioMode = AudioMode.LIVE_HEADSET,
        liveModeId = "M0", profilePreference = ProfileTier.HYBRID,
        tagline = "手机在口袋，对方说什么耳机里听译文",
    )
    val quickPhrase = ScenePreset(
        id = "quick_phrase", kind = SceneKind.LIVE_TALK, subScene = SubScene.QUICK_PHRASE, name = "速译一句", version = 1,
        route = RoutePolicy.AUTO, langChips = listOf(LangChip(Lang.ZH_CN, default = true)),
        style = Style.CASUAL, translationTargets = listOf(Lang.EN), templateId = "phrase_card",
        privacy = PrivacyMode.TextOnlyCloud, hotwordBucket = "travel", audioMode = AudioMode.DEFAULT,
        liveModeId = "M4", profilePreference = ProfileTier.HYBRID,
        tagline = "说一句，大字朝向对方",
    )
    val meeting = ScenePreset(
        id = "meeting", kind = SceneKind.MEETING, name = "会议", version = 1,
        route = RoutePolicy.AUTO, refineByDefault = true,
        langChips = listOf(LangChip(Lang.ZH_CN, default = true, shownOnCard = true), LangChip(Lang.EN, shownOnCard = true)),
        style = Style.BUSINESS, translationTargets = emptyList(), templateId = "meeting_minutes",
        privacy = PrivacyMode.TextOnlyCloud, hotwordBucket = "work", audioMode = AudioMode.DEFAULT,
        tagline = "一按即录，10 分钟内交出能分享的纪要",
    )
    val screenFile = ScenePreset(
        id = "screen_file", kind = SceneKind.SCREEN_IN, subScene = SubScene.VIDEO_SUBTITLE, name = "视频字幕", version = 1,
        route = RoutePolicy.AUTO, langChips = listOf(LangChip(Lang.EN, default = true, shownOnCard = true), LangChip(Lang.ZH_CN)),
        style = Style.NEUTRAL, translationTargets = listOf(Lang.ZH_CN), templateId = "subtitle_track",
        privacy = PrivacyMode.TextOnlyCloud, hotwordBucket = "general", audioMode = AudioMode.MEDIA,
        screenModeId = "S4", vad = VadProfile.media(),
        captionStyle = CaptionStyle(host = CaptionHostKind.IN_APP_PLAYER),
        tagline = "相册 / 文件 / 直链视频，边转边看，导出 SRT",
    )

    val mvp: List<ScenePreset> = listOf(liveTalk, listenOnly, quickPhrase, meeting, screenFile)
    fun byId(id: String): ScenePreset? = mvp.firstOrNull { it.id == id }

    /** 内置场景的名字 / 副标题按界面语言显示；`name` / `tagline` 字段留作中文源与自定义场景的用户输入。 */
    fun nameRes(id: String): StringResource? = when (id) {
        liveTalk.id -> Res.string.scene_live_talk; listenOnly.id -> Res.string.scene_listen; quickPhrase.id -> Res.string.scene_quick_phrase
        meeting.id -> Res.string.scene_meeting; screenFile.id -> Res.string.scene_screen_file; else -> null
    }
    fun taglineRes(id: String): StringResource? = when (id) {
        liveTalk.id -> Res.string.scene_live_talk_tagline; listenOnly.id -> Res.string.scene_listen_tagline; quickPhrase.id -> Res.string.scene_quick_phrase_tagline
        meeting.id -> Res.string.scene_meeting_tagline; screenFile.id -> Res.string.scene_screen_file_tagline; else -> null
    }
}
