package dev.scenenote.asr

import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.PrivacyMode
import dev.scenenote.core.model.RoutePolicy
import dev.scenenote.core.model.Scenes
import kotlin.test.Test
import kotlin.test.assertEquals

/** RoutePolicy × PrivacyMode 组合矩阵（02 篇 §2.5）的关键格子。 */
class EngineSelectorTest {
    private val selector = DefaultEngineSelector()
    private fun ctx(privacy: PrivacyMode, lang: String = Lang.ZH_CN, online: Boolean = true, key: Boolean = true, blocked: Boolean = false) =
        SelectorContext(privacy = privacy, lang = lang, online = online, hasCloudKey = key, spendBlocked = blocked)

    @Test fun lockedNeverLeavesDevice() {
        val r = selector.decide(Scenes.meeting, ctx(PrivacyMode.Locked))
        assertEquals(RouteKind.LocalOnly, r.kind)
    }

    @Test fun lockedRefusesUncoveredDialect() {
        val r = selector.decide(Scenes.meeting, ctx(PrivacyMode.Locked, lang = Lang.WUU))
        assertEquals(RouteKind.Refused, r.kind)
    }

    @Test fun consentModeOffersOptionalCloud() {
        val r = selector.decide(Scenes.meeting, ctx(PrivacyMode.LocalWithPerSegmentConsent))
        assertEquals(RouteKind.LocalFinalCloudOptional, r.kind)
    }

    @Test fun textOnlyPrivacyCannotRefineAudio() {
        // 会议卡 refineByDefault=true，但 TextOnlyCloud 不允许音频上云 → 只能本地定稿。
        val r = selector.decide(Scenes.meeting, ctx(PrivacyMode.TextOnlyCloud))
        assertEquals(RouteKind.LocalFinalCloudOptional, r.kind)
    }

    @Test fun audioCloudRefinesByDefault() {
        val r = selector.decide(Scenes.meeting, ctx(PrivacyMode.AudioCloud))
        assertEquals(RouteKind.LocalDraftCloudRefine, r.kind)
    }

    @Test fun uncoveredDialectGoesCloudOnlyWhenAllowed() {
        val r = selector.decide(Scenes.meeting, ctx(PrivacyMode.AudioCloud, lang = Lang.WUU))
        assertEquals(RouteKind.CloudOnly, r.kind)
    }

    @Test fun uncoveredDialectRefusedOffline() {
        val r = selector.decide(Scenes.meeting, ctx(PrivacyMode.AudioCloud, lang = Lang.WUU, online = false))
        assertEquals(RouteKind.Refused, r.kind)
    }

    @Test fun spendGateBlocksCloud() {
        val r = selector.decide(Scenes.meeting.copy(route = RoutePolicy.CLOUD_FIRST), ctx(PrivacyMode.AudioCloud, blocked = true))
        assertEquals(RouteKind.LocalFinalCloudOptional, r.kind)
    }

    @Test fun cloudFirstUsesCloudWhenAllowed() {
        val r = selector.decide(Scenes.meeting.copy(route = RoutePolicy.CLOUD_FIRST), ctx(PrivacyMode.AudioCloud))
        assertEquals(RouteKind.CloudFirst, r.kind)
    }
}
