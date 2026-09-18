package dev.scenenote.ui.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.ButtonStyle
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
import dev.scenenote.core.i18n.string
import dev.scenenote.shared.resources.*
import dev.scenenote.ui.i18n.langName
import dev.scenenote.ui.i18n.title
import org.jetbrains.compose.resources.StringResource
import dev.scenenote.core.i18n.stringResource

/** 可作底版的内置场景（01 篇：高级选项只在「复制一张再改」里出现）。 */
internal val BASES: List<ScenePreset> = listOf(Scenes.liveTalk, Scenes.listenOnly, Scenes.quickPhrase, Scenes.meeting)
private val LANGS = listOf(Lang.ZH_CN, Lang.EN, Lang.YUE_HK, Lang.ZH_SICHUAN, Lang.JA, Lang.KO)
private val STYLES = listOf(Style.NEUTRAL, Style.FORMAL, Style.CASUAL, Style.BUSINESS)
private val ROUTES = listOf(RoutePolicy.AUTO, RoutePolicy.LOCAL_ONLY, RoutePolicy.CLOUD_FIRST)
private val PRIVACIES: List<PrivacyMode> = listOf(PrivacyMode.Locked, PrivacyMode.LocalWithPerSegmentConsent, PrivacyMode.TextOnlyCloud, PrivacyMode.AudioCloud)
private data class Bucket(val id: String, val label: StringResource)
private val BUCKETS = listOf(Bucket("general", Res.string.bucket_general), Bucket("travel", Res.string.bucket_travel), Bucket("work", Res.string.bucket_work))

internal fun styleLabelRes(s: Style): StringResource = when (s) { Style.NEUTRAL -> Res.string.style_neutral; Style.FORMAL -> Res.string.style_formal; Style.CASUAL -> Res.string.style_casual; Style.BUSINESS -> Res.string.style_business; Style.ACADEMIC -> Res.string.style_academic; Style.SOCIAL -> Res.string.style_social; Style.CUSTOM -> Res.string.style_custom }
@Composable internal fun styleLabel(s: Style): String = stringResource(styleLabelRes(s))
@Composable internal fun routeLabel(r: RoutePolicy): String = stringResource(when (r) { RoutePolicy.AUTO -> Res.string.route_auto; RoutePolicy.LOCAL_ONLY -> Res.string.route_local_only; RoutePolicy.CLOUD_FIRST -> Res.string.route_cloud_first })
@Composable internal fun privacyLabel(m: PrivacyMode): String = dev.scenenote.ui.settings.privacyLabel(m)
@Composable internal fun bucketLabel(id: String): String = BUCKETS.firstOrNull { it.id == id }?.let { stringResource(it.label) } ?: id

/**
 * 场景编辑 sheet「复制一张再改」（I7）：名字 / 语言 / 风格 / 识别方式 / 联网权限 / 术语。
 * 「优先云端」× 不联网这类不合法组合行内提示、完成键置灰，不弹窗。editing 非空 = 改已有副本（可删除）。
 */
@Composable
fun BoxScope.SceneEditSheet(visible: Boolean, store: SceneStore, editing: CustomScene?, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf<CustomScene?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    // 副本默认名按界面语言：「面对面对话 副本」/ “Face-to-face copy”
    val copyNames = BASES.associate { it.id to stringResource(Res.string.scene_copy_name, it.title()) }
    LaunchedEffect(visible, editing) { if (visible) { draft = editing ?: store.copyOf(Scenes.liveTalk, copyNames.getValue(Scenes.liveTalk.id)); expanded = null } }
    val d = draft
    val problem = d?.let { SceneRules.problem(it.route, PrivacyMode.fromId(it.privacyId)) }
    val c = SceneTheme.colors
    // 玻璃取样源由宿主决定：壳覆盖层里能取样内容层做模糊；内联在内容层里的宿主要把 LocalGlassBackdrop 置空
    SceneSheet(
        visible = visible, onDismiss = onDismiss, title = stringResource(if (editing == null) Res.string.home_copy_scene else Res.string.scene_edit_title),
        doneText = stringResource(Res.string.key_save),
        onDone = { if (d != null && problem == null && d.name.isNotBlank()) { store.upsert(d); onDismiss() } },
    ) {
        if (d == null) return@SceneSheet
        fun toggle(k: String) { expanded = if (expanded == k) null else k }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = SceneSpacing.s, bottom = 40.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(SceneSpacing.l)) {
            Column {
                SceneSectionHeader(stringResource(Res.string.scene_edit_name))
                SceneGroup {
                    Box(Modifier.padding(horizontal = SceneSpacing.row, vertical = 10.dp)) {
                        InlineField(d.name, { draft = d.copy(name = it) }, stringResource(Res.string.scene_edit_name_placeholder), Modifier.fillMaxWidth())
                    }
                    if (editing == null) {
                        SceneDivider(inset = SceneSpacing.row)
                        SceneRow(stringResource(Res.string.scene_edit_based_on), value = Scenes.byId(d.baseId)?.title() ?: d.baseId, chevron = true, onClick = { toggle("base") })
                        InlineOptions(expanded == "base", BASES, Scenes.byId(d.baseId) ?: Scenes.liveTalk, label = { it.title() }) { b ->
                            draft = store.copyOf(b, name = d.name.takeIf { it.isNotBlank() && it != copyNames[d.baseId] } ?: copyNames.getValue(b.id)); expanded = null
                        }
                    }
                }
            }
            Column {
                SceneSectionHeader(stringResource(Res.string.settings_lang_header))
                SceneGroup {
                    SceneRow(stringResource(Res.string.settings_lang_mine), value = langName(d.myLang), chevron = true, onClick = { toggle("my") })
                    InlineOptions(expanded == "my", LANGS, d.myLang, label = { langName(it) }) { draft = d.copy(myLang = it); expanded = null }
                    SceneDivider(inset = SceneSpacing.row)
                    SceneRow(stringResource(Res.string.common_other), value = langName(d.otherLang), chevron = true, onClick = { toggle("other") })
                    InlineOptions(expanded == "other", LANGS, d.otherLang, label = { langName(it) }) { draft = d.copy(otherLang = it); expanded = null }
                }
            }
            Column {
                SceneSectionHeader(stringResource(Res.string.scene_edit_translation))
                SceneGroup {
                    SceneRow(stringResource(Res.string.scene_edit_tone), value = styleLabel(d.style), chevron = true, onClick = { toggle("style") })
                    InlineOptions(expanded == "style", STYLES, d.style, label = { styleLabel(it) }) { draft = d.copy(style = it); expanded = null }
                    SceneDivider(inset = SceneSpacing.row)
                    SceneRow(stringResource(Res.string.scene_edit_terms), value = bucketLabel(d.bucket), chevron = true, onClick = { toggle("bucket") })
                    InlineOptions(expanded == "bucket", BUCKETS, BUCKETS.firstOrNull { it.id == d.bucket } ?: BUCKETS.first(), label = { stringResource(it.label) }) { draft = d.copy(bucket = it.id); expanded = null }
                }
                SceneSectionFooter(stringResource(Res.string.scene_edit_terms_footer))
            }
            Column {
                SceneSectionHeader(stringResource(Res.string.scene_edit_network))
                SceneGroup {
                    SceneRow(stringResource(Res.string.scene_edit_recognition), value = routeLabel(d.route), chevron = true, onClick = { toggle("route") })
                    InlineOptions(expanded == "route", ROUTES, d.route, label = { routeLabel(it) }) { draft = d.copy(route = it); expanded = null }
                    SceneDivider(inset = SceneSpacing.row)
                    SceneRow(stringResource(Res.string.settings_net_header), value = privacyLabel(PrivacyMode.fromId(d.privacyId)), chevron = true, onClick = { toggle("privacy") })
                    InlineOptions(expanded == "privacy", PRIVACIES, PrivacyMode.fromId(d.privacyId), label = { privacyLabel(it) }) { draft = d.copy(privacyId = PrivacyMode.idOf(it)); expanded = null }
                }
                if (problem != null) SceneText(problem.string(), Modifier.padding(horizontal = SceneSpacing.page, vertical = 6.dp), style = SceneTheme.type.footnote, color = c.destructive)
                else SceneSectionFooter(stringResource(Res.string.scene_edit_footer))
            }
            if (editing != null) Box(Modifier.padding(horizontal = SceneSpacing.page, vertical = SceneSpacing.m)) {
                SceneButton(stringResource(Res.string.scene_edit_delete), onClick = { store.delete(editing.id); onDismiss() }, style = ButtonStyle.Destructive, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
