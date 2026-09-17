package dev.scenenote.ui.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.LocalGlassBackdrop
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneRow
import dev.scenenote.core.designsystem.SceneSectionFooter
import dev.scenenote.core.designsystem.SceneSectionHeader
import dev.scenenote.core.designsystem.SceneSheet
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.PrivacyMode
import dev.scenenote.core.model.RoutePolicy
import dev.scenenote.core.model.ScenePreset
import dev.scenenote.core.model.Scenes
import dev.scenenote.core.model.Style
import dev.scenenote.core.scene.CustomScene
import dev.scenenote.core.scene.SceneRules
import dev.scenenote.core.scene.SceneStore
import dev.scenenote.ui.common.InlineField
import dev.scenenote.ui.common.InlineOptions

/** 可作底版的内置场景（01 篇：高级选项只在「复制一张再改」里出现）。 */
internal val BASES: List<ScenePreset> = listOf(Scenes.liveTalk, Scenes.listenOnly, Scenes.quickPhrase, Scenes.meeting)
private val LANGS = listOf(Lang.ZH_CN, Lang.EN, Lang.YUE_HK, Lang.ZH_SICHUAN, Lang.JA, Lang.KO)
private val STYLES = listOf(Style.NEUTRAL, Style.FORMAL, Style.CASUAL, Style.BUSINESS)
private val ROUTES = listOf(RoutePolicy.AUTO, RoutePolicy.LOCAL_ONLY, RoutePolicy.CLOUD_FIRST)
private val PRIVACIES: List<PrivacyMode> = listOf(PrivacyMode.Locked, PrivacyMode.LocalWithPerSegmentConsent, PrivacyMode.TextOnlyCloud, PrivacyMode.AudioCloud)
private data class Bucket(val id: String, val label: String)
private val BUCKETS = listOf(Bucket("general", "通用"), Bucket("travel", "出行"), Bucket("work", "工作"))

internal fun styleLabel(s: Style): String = when (s) { Style.NEUTRAL -> "自然"; Style.FORMAL -> "正式"; Style.CASUAL -> "随意"; Style.BUSINESS -> "商务"; Style.ACADEMIC -> "学术"; Style.SOCIAL -> "社交"; Style.CUSTOM -> "自定义" }
internal fun routeLabel(r: RoutePolicy): String = when (r) { RoutePolicy.AUTO -> "自动"; RoutePolicy.LOCAL_ONLY -> "只用本机"; RoutePolicy.CLOUD_FIRST -> "优先云端" }
internal fun privacyLabel(m: PrivacyMode): String = when (m) { PrivacyMode.Locked -> "不联网"; PrivacyMode.LocalWithPerSegmentConsent -> "每次询问"; PrivacyMode.TextOnlyCloud -> "只发文字"; PrivacyMode.AudioCloud -> "文字和录音" }
internal fun bucketLabel(id: String): String = BUCKETS.firstOrNull { it.id == id }?.label ?: id

/**
 * 场景编辑 sheet「复制一张再改」（I7）：名字 / 语言 / 风格 / 识别方式 / 联网权限 / 术语。
 * 「优先云端」× 不联网这类不合法组合行内提示、完成键置灰，不弹窗。editing 非空 = 改已有副本（可删除）。
 */
@Composable
fun BoxScope.SceneEditSheet(visible: Boolean, store: SceneStore, editing: CustomScene?, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf<CustomScene?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(visible, editing) { if (visible) { draft = editing ?: store.copyOf(Scenes.liveTalk); expanded = null } }
    val d = draft
    val problem = d?.let { SceneRules.problem(it.route, PrivacyMode.fromId(it.privacyId)) }
    val c = SceneTheme.colors
    CompositionLocalProvider(LocalGlassBackdrop provides null) {
        SceneSheet(
            visible = visible, onDismiss = onDismiss, title = if (editing == null) "复制一张再改" else "改场景",
            doneText = "保存",
            onDone = { if (d != null && problem == null && d.name.isNotBlank()) { store.upsert(d); onDismiss() } },
        ) {
            if (d == null) return@SceneSheet
            fun toggle(k: String) { expanded = if (expanded == k) null else k }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = SceneSpacing.s, bottom = 40.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(SceneSpacing.l)) {
                Column {
                    SceneSectionHeader("名字")
                    SceneGroup {
                        Box(Modifier.padding(horizontal = SceneSpacing.row, vertical = 10.dp)) {
                            InlineField(d.name, { draft = d.copy(name = it) }, "给这张场景起个名字", Modifier.fillMaxWidth())
                        }
                        if (editing == null) {
                            SceneDivider(inset = SceneSpacing.row)
                            SceneRow("基于", value = Scenes.byId(d.baseId)?.name ?: d.baseId, chevron = true, onClick = { toggle("base") })
                            InlineOptions(expanded == "base", BASES, Scenes.byId(d.baseId) ?: Scenes.liveTalk, label = { it.name }) { b ->
                                draft = store.copyOf(b, name = d.name.takeIf { it.isNotBlank() && it != "${Scenes.byId(d.baseId)?.name} 副本" } ?: "${b.name} 副本"); expanded = null
                            }
                        }
                    }
                }
                Column {
                    SceneSectionHeader("语言")
                    SceneGroup {
                        SceneRow("我的", value = Lang.displayName(d.myLang), chevron = true, onClick = { toggle("my") })
                        InlineOptions(expanded == "my", LANGS, d.myLang, label = { Lang.displayName(it) }) { draft = d.copy(myLang = it); expanded = null }
                        SceneDivider(inset = SceneSpacing.row)
                        SceneRow("对方", value = Lang.displayName(d.otherLang), chevron = true, onClick = { toggle("other") })
                        InlineOptions(expanded == "other", LANGS, d.otherLang, label = { Lang.displayName(it) }) { draft = d.copy(otherLang = it); expanded = null }
                    }
                }
                Column {
                    SceneSectionHeader("翻译")
                    SceneGroup {
                        SceneRow("语气", value = styleLabel(d.style), chevron = true, onClick = { toggle("style") })
                        InlineOptions(expanded == "style", STYLES, d.style, label = ::styleLabel) { draft = d.copy(style = it); expanded = null }
                        SceneDivider(inset = SceneSpacing.row)
                        SceneRow("术语", value = bucketLabel(d.bucket), chevron = true, onClick = { toggle("bucket") })
                        InlineOptions(expanded == "bucket", BUCKETS, BUCKETS.firstOrNull { it.id == d.bucket } ?: BUCKETS.first(), label = { it.label }) { draft = d.copy(bucket = it.id); expanded = null }
                    }
                    SceneSectionFooter("术语表里对应分组的译名会优先使用")
                }
                Column {
                    SceneSectionHeader("联网")
                    SceneGroup {
                        SceneRow("识别", value = routeLabel(d.route), chevron = true, onClick = { toggle("route") })
                        InlineOptions(expanded == "route", ROUTES, d.route, label = ::routeLabel) { draft = d.copy(route = it); expanded = null }
                        SceneDivider(inset = SceneSpacing.row)
                        SceneRow("联网权限", value = privacyLabel(PrivacyMode.fromId(d.privacyId)), chevron = true, onClick = { toggle("privacy") })
                        InlineOptions(expanded == "privacy", PRIVACIES, PrivacyMode.fromId(d.privacyId), label = ::privacyLabel) { draft = d.copy(privacyId = PrivacyMode.idOf(it)); expanded = null }
                    }
                    if (problem != null) SceneText(problem, Modifier.padding(horizontal = SceneSpacing.page, vertical = 6.dp), style = SceneTheme.type.footnote, color = c.destructive)
                    else SceneSectionFooter("这张场景自己的设置，不影响别的场景")
                }
                if (editing != null) Box(Modifier.padding(horizontal = SceneSpacing.page, vertical = SceneSpacing.m)) {
                    SceneButton("删除这张场景", onClick = { store.delete(editing.id); onDismiss() }, style = ButtonStyle.Destructive, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
