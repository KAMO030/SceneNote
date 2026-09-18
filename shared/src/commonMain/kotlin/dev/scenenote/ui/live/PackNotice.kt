package dev.scenenote.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.scenenote.asr.LocalEngineState
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.Interaction
import dev.scenenote.core.i18n.stringResource
import dev.scenenote.ui.i18n.langName
import dev.scenenote.shared.resources.Res
import dev.scenenote.shared.resources.live_asr_failed
import dev.scenenote.shared.resources.live_download
import dev.scenenote.shared.resources.live_nmt_unsupported
import dev.scenenote.shared.resources.live_pack_loading
import dev.scenenote.shared.resources.live_pack_need_asr
import dev.scenenote.shared.resources.live_pack_need_asr_lang
import dev.scenenote.shared.resources.live_pack_need_mt
import dev.scenenote.shared.resources.live_pack_need_more
import dev.scenenote.shared.resources.live_tts_unavailable

/**
 * 四个实时布局共用的行内提示（不弹窗），一次只说一件事，按"没它就跑不起来"排序：
 * 缺包 → 说清缺的是识别还是翻译、哪门语言，「去下载」带着缺的包 id 进语音包页（排在最前，用户自己点下载）；
 * 包齐了引擎还是起不来 → 说识别启动失败，不给下载按钮（下载解决不了）；装载中 → 灰字；
 * 这对语言没有端侧翻译（如英 → 韩）→ 只提示一句；
 * 开着朗读却没有任何可用语音（日语没有端侧语音包，系统也没装日语音色）→ 说清只出字，免得以为是卡住了。
 */
@Composable
internal fun PackNotice(ui: LiveUiState, onOpenModels: (List<String>) -> Unit, centered: Boolean = false) {
    when (noticeOf(ui)) {
        PackNoticeKind.NEED_PACKS -> ActionNotice(needPacksText(ui), centered) { onOpenModels(ui.packsMissing) }
        PackNoticeKind.ASR_FAILED -> PlainNotice(stringResource(Res.string.live_asr_failed), centered, destructive = true)
        PackNoticeKind.LOADING -> PlainNotice(stringResource(Res.string.live_pack_loading), centered, destructive = false)
        PackNoticeKind.UNSUPPORTED -> PlainNotice(stringResource(Res.string.live_nmt_unsupported), centered, destructive = false)
        PackNoticeKind.NO_VOICE -> PlainNotice(stringResource(Res.string.live_tts_unavailable, langName(ttsLangOf(ui))), centered, destructive = false)
        PackNoticeKind.NONE -> Unit
    }
}

/** 提示种类。引擎状态在这个普通函数里归约成枚举，composable 里就只剩一次 `when`（K/N 对同一 sealed 主语连写两个 when 会崩，见 ModelsScreen 注释）。 */
private enum class PackNoticeKind { NONE, NEED_PACKS, ASR_FAILED, LOADING, UNSUPPORTED, NO_VOICE }

private fun noticeOf(ui: LiveUiState): PackNoticeKind = when {
    ui.packsMissing.isNotEmpty() -> PackNoticeKind.NEED_PACKS
    ui.engine is LocalEngineState.Error -> PackNoticeKind.ASR_FAILED
    ui.engine is LocalEngineState.Loading -> PackNoticeKind.LOADING
    ui.nmtUnsupported -> PackNoticeKind.UNSUPPORTED
    // 自己关了朗读、热档暂停朗读（reasonCode = off）不算"没有语音"，那是用户 / 阶梯自己的选择
    ui.voiceOut && ui.health.tts == "none" && ui.health.ttsEngine != "off" -> PackNoticeKind.NO_VOICE
    else -> PackNoticeKind.NONE
}

/** 朗读的目标语言：速译是说给对方听，其余都是念给我听（与 FastPath.refreshTtsHealth 同口径）。 */
private fun ttsLangOf(ui: LiveUiState): String =
    if (ui.mode?.interaction == Interaction.SIMPLEX_OUT) ui.otherLang else ui.myLang

/**
 * 缺包一句话。只缺一样就说那一样；缺专属识别包时点名语言（「日语的识别包还没下载」——用户最容易以为下了翻译包就够了）；
 * 混着缺就笼统说"几个包"，具体清单在语音包页顶上。
 */
@Composable
private fun needPacksText(ui: LiveUiState): String {
    val lang = ui.asrMissingLangs.singleOrNull()
    return when {
        !ui.asrBaseMissing && ui.asrMissingLangs.isEmpty() -> stringResource(Res.string.live_pack_need_mt)
        !ui.mtMissing && ui.asrBaseMissing && ui.asrMissingLangs.isEmpty() -> stringResource(Res.string.live_pack_need_asr)
        !ui.mtMissing && !ui.asrBaseMissing && lang != null -> stringResource(Res.string.live_pack_need_asr_lang, langName(lang))
        else -> stringResource(Res.string.live_pack_need_more)
    }
}

@Composable
private fun ActionNotice(text: String, centered: Boolean, onDownload: () -> Unit) {
    val c = SceneTheme.colors
    if (centered) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
        SceneText(text, style = SceneTheme.type.footnote, color = c.destructive, textAlign = TextAlign.Center)
        SceneButton(stringResource(Res.string.live_download), onClick = onDownload, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically) {
        SceneText(text, Modifier.weight(1f), style = SceneTheme.type.footnote, color = c.destructive)
        SceneButton(stringResource(Res.string.live_download), onClick = onDownload, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
    }
}

@Composable
private fun PlainNotice(text: String, centered: Boolean, destructive: Boolean) {
    val c = SceneTheme.colors
    SceneText(
        text, Modifier.fillMaxWidth(), style = SceneTheme.type.footnote,
        color = if (destructive) c.destructive else c.secondaryLabel,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
    )
}
