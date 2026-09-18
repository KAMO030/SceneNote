package dev.scenenote.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneSectionFooter
import dev.scenenote.core.designsystem.SceneSectionHeader
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.models.ModelCatalog
import dev.scenenote.models.ModelPack
import dev.scenenote.models.ModelState
import dev.scenenote.models.ModelStore
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import dev.scenenote.shared.resources.*
import dev.scenenote.core.i18n.stringResource

class ModelsViewModel(val store: ModelStore) : ViewModel() {
    init { viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) { store.pruneOrphans() }; store.refresh() }
    fun install(p: ModelPack) { viewModelScope.launch { store.install(p).collect() } }
    fun delete(p: ModelPack) = store.delete(p)
}

/** 用户看到的包名（docs/15 §1：模型名不上页面）；按 pack.id 对应，没对上的退回目录名。 */
@Composable
private fun displayName(pack: ModelPack): String = when (pack.id) {
    "zipformer-zh-en-streaming" -> stringResource(Res.string.pack_zipformer)
    "matcha-tts-zh-en" -> stringResource(Res.string.pack_matcha)
    "paraformer-zh-sichuan" -> stringResource(Res.string.pack_paraformer_sichuan)
    "sense-voice-zh-en-ja-ko-yue" -> stringResource(Res.string.pack_sensevoice)
    "3dspeaker-campplus" -> stringResource(Res.string.pack_speaker)
    "silero-vad" -> stringResource(Res.string.pack_vad)
    "nmt-zh-en" -> stringResource(Res.string.pack_nmt_zh_en)
    "nmt-en-zh" -> stringResource(Res.string.pack_nmt_en_zh)
    "nmt-ja-en" -> stringResource(Res.string.pack_nmt_ja_en)
    "nmt-en-ja" -> stringResource(Res.string.pack_nmt_en_ja)
    "nmt-ko-en" -> stringResource(Res.string.pack_nmt_ko_en)
    else -> pack.name
}

/**
 * 设置 → 语音包：每行只有名称、大小、状态按钮。
 * [autoInstall]：进页即下载（只给 `scenenote://models?install=` 深链 / 验收用）。
 * [need]：实时页「去下载」带来的缺包，单独排在最前面，由用户自己点下载——不替用户开下没点过的包。
 */
@Composable
fun ModelsScreen(onBack: () -> Unit, autoInstall: List<String> = emptyList(), need: List<String> = emptyList(), vm: ModelsViewModel = koinViewModel()) {
    val states by vm.store.states.collectAsState()
    // 只触发一次（Activity 重建不重复），且跳过正在下载的包
    var autoRan by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoRan) return@LaunchedEffect
        autoRan = true
        autoInstall.mapNotNull { ModelCatalog.byId(it) }.forEach { if (!vm.store.isInstalled(it) && vm.store.states.value[it.id] !is ModelState.Downloading) vm.install(it) }
    }
    val needed = need.mapNotNull { ModelCatalog.byId(it) }.distinct()
    val rest = ModelCatalog.all.filterNot { it in needed }
    GlassScaffold(topBar = { SceneNavBar(title = stringResource(Res.string.settings_packs_header), onBack = onBack) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 104.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            if (needed.isNotEmpty()) Column {
                SceneSectionHeader(stringResource(Res.string.pack_needed_header))
                PackGroup(needed, states, vm)
            }
            Column {
                PackGroup(rest, states, vm)
                SceneSectionFooter(stringResource(Res.string.pack_footer))
            }
        }
    }
}

@Composable
private fun PackGroup(packs: List<ModelPack>, states: Map<String, ModelState>, vm: ModelsViewModel) {
    SceneGroup {
        packs.forEachIndexed { i, pack ->
            if (i > 0) SceneDivider()
            ModelPackRow(pack, states[pack.id] ?: ModelState.NotInstalled, onInstall = { vm.install(pack) }, onDelete = { vm.delete(pack) })
        }
    }
}

/**
 * 一行：名称 / 大小 + 状态词；尾随「下载」「删除」「重试」；下载中显示进度条。
 *
 * 状态词与尾随按钮拆成两个 composable，各自只对 [st] 做一次 `when`，不要合回一个函数里：
 * Kotlin/Native（2.4.10 + CMP 1.12.0）下，同一个 composable 里对同一 sealed 主语连写两个 `when`，
 * 且前一个 `when` 的分支把 `@ReadOnlyComposable` 取值（`SceneTheme.type.footnote`）当实参传给 composable，
 * 后一个 `when` 的 `is` 判断会失效并抛 NoWhenBranchMatchedException（iOS 打开本页 / 点下载即崩，Android 正常）。
 */
@Composable
private fun ModelPackRow(pack: ModelPack, st: ModelState, onInstall: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = SceneSize.rowMinHeight).padding(horizontal = SceneSpacing.row, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SceneText(displayName(pack), style = SceneTheme.type.body, maxLines = 1)
            PackStatus(pack, st)
        }
        PackAction(st, onInstall = onInstall, onDelete = onDelete)
    }
}

/** 名称下的状态词：已下载 / 进度 / 失败 / 大小。 */
@Composable
private fun PackStatus(pack: ModelPack, st: ModelState) {
    val c = SceneTheme.colors
    when (st) {
        is ModelState.Installed -> SceneText(stringResource(Res.string.pack_installed, mb(st.bytes).toInt()), style = SceneTheme.type.footnote, color = c.tint, maxLines = 1)
        is ModelState.Downloading -> {
            val frac = if (st.bytesTotal > 0) (st.bytesDone.toFloat() / st.bytesTotal).coerceIn(0f, 1f) else 0f
            DownloadProgress(frac)
            SceneText(
                if (st.bytesTotal > 0) "${mb(st.bytesDone)} / ${mb(st.bytesTotal)} MB" else stringResource(Res.string.pack_downloading),
                style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1,
            )
        }
        is ModelState.Failed -> SceneText(stringResource(Res.string.pack_failed, mb(pack.totalBytes).toInt()), style = SceneTheme.type.footnote, color = c.destructive, maxLines = 1)
        is ModelState.NotInstalled -> SceneText("${mb(pack.totalBytes)} MB", style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1)
    }
}

/** 尾随按钮：下载 / 删除 / 重试；下载中不显示。 */
@Composable
private fun PackAction(st: ModelState, onInstall: () -> Unit, onDelete: () -> Unit) {
    when (st) {
        is ModelState.Installed -> SceneButton(stringResource(Res.string.common_delete), onClick = onDelete, style = ButtonStyle.Destructive, height = SceneSize.glassButton)
        is ModelState.Downloading -> Unit
        is ModelState.Failed -> SceneButton(stringResource(Res.string.common_retry), onClick = onInstall, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
        is ModelState.NotInstalled -> SceneButton(stringResource(Res.string.pack_download), onClick = onInstall, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
    }
}

/** 下载进度条：4 dp 胶囊轨道（fill）+ 着色进度。 */
@Composable
private fun DownloadProgress(frac: Float) {
    val c = SceneTheme.colors
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.fill)) {
        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(c.tint))
    }
}

/** 向上取整到 MB，0.6 MB 的包显示 1 MB 而不是 0。 */
private fun mb(bytes: Long): Long = (bytes + 1_048_575) / 1_048_576
