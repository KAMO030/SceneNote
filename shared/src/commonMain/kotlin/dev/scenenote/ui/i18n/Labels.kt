package dev.scenenote.ui.i18n

import androidx.compose.runtime.Composable
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.ModeSpec
import dev.scenenote.core.model.ModeSpecs
import dev.scenenote.core.model.ScenePreset
import dev.scenenote.core.model.Scenes
import dev.scenenote.shared.resources.*
import kotlinx.datetime.number
import dev.scenenote.core.i18n.stringResource

/** 语言名（普通话 / Mandarin / 中国語 …）；未知标签原样显示。 */
@Composable
fun langName(tag: String): String = Lang.nameRes(tag)?.let { stringResource(it) } ?: tag

/** 场景名：内置场景按界面语言，自定义场景用用户起的名字。 */
@Composable
fun ScenePreset.title(): String = Scenes.nameRes(id)?.let { stringResource(it) } ?: name

@Composable
fun ScenePreset.taglineText(): String = Scenes.taglineRes(id)?.let { stringResource(it) } ?: tagline

@Composable
fun ModeSpec.title(): String = stringResource(ModeSpecs.nameRes(id))

@Composable
fun ModeSpec.summaryText(): String = stringResource(ModeSpecs.summaryRes(id))

/** 列表分组的日期标签：今天 / 昨天 / 9月17日（跨年带年份）；英文用月份缩写。 */
@Composable
fun dayLabel(date: kotlinx.datetime.LocalDate, today: kotlinx.datetime.LocalDate): String = when (today.toEpochDays() - date.toEpochDays()) {
    0L -> stringResource(Res.string.date_today)
    1L -> stringResource(Res.string.date_yesterday)
    else -> {
        val month = kotlinx.datetime.format.MonthNames.ENGLISH_ABBREVIATED.names[date.month.ordinal]
        if (date.year == today.year) stringResource(Res.string.date_md, date.month.number, date.day, month)
        else stringResource(Res.string.date_ymd, date.month.number, date.day, month, date.year)
    }
}

/**
 * 给「对方语言」的文字选对应 locale 的字体：同一个汉字在中 / 日字体里字形不同（直、骨、角…），
 * 界面语言是中文时，对方半屏的日文若不指定 locale 会按中文字形渲染。
 */
fun androidx.compose.ui.text.TextStyle.forLang(tag: String): androidx.compose.ui.text.TextStyle =
    copy(localeList = androidx.compose.ui.text.intl.LocaleList(Lang.bcp47(tag)))
