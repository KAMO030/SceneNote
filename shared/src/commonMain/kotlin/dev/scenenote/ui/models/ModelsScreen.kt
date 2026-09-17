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

class ModelsViewModel(val store: ModelStore) : ViewModel() {
    init { store.refresh() }
    fun install(p: ModelPack) { viewModelScope.launch { store.install(p).collect() } }
    fun delete(p: ModelPack) = store.delete(p)
}

/** 用户看到的包名（docs/15 §1：模型名不上页面）；按 pack.id 对应，没对上的退回目录名。 */
private fun displayName(pack: ModelPack): String = when (pack.id) {
    "zipformer-zh-en-streaming" -> "中英识别"
    "matcha-tts-zh-en" -> "中英朗读"
    "paraformer-zh-sichuan" -> "四川话识别"
    "sense-voice-zh-en-ja-ko-yue" -> "多语言识别"
    "3dspeaker-campplus" -> "说话人分辨"
    "silero-vad" -> "静音检测"
    else -> pack.name
}

/** 设置 → 语音包：每行只有名称、大小、状态按钮。 */
@Composable
fun ModelsScreen(onBack: () -> Unit, autoInstall: List<String> = emptyList(), vm: ModelsViewModel = koinViewModel()) {
    val states by vm.store.states.collectAsState()
    // 只触发一次（Activity 重建不重复），且跳过正在下载的包
    var autoRan by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoRan) return@LaunchedEffect
        autoRan = true
        autoInstall.mapNotNull { ModelCatalog.byId(it) }.forEach { if (!vm.store.isInstalled(it) && vm.store.states.value[it.id] !is ModelState.Downloading) vm.install(it) }
    }
    GlassScaffold(topBar = { SceneNavBar(title = "语音包", onBack = onBack) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 104.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            Column {
                SceneGroup {
                    ModelCatalog.all.forEachIndexed { i, pack ->
                        if (i > 0) SceneDivider()
                        ModelPackRow(pack, states[pack.id] ?: ModelState.NotInstalled, onInstall = { vm.install(pack) }, onDelete = { vm.delete(pack) })
                    }
                }
                SceneSectionFooter("按需下载，只存本机")
            }
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
        is ModelState.Installed -> SceneText("${mb(st.bytes)} MB · 已下载", style = SceneTheme.type.footnote, color = c.tint, maxLines = 1)
        is ModelState.Downloading -> {
            val frac = if (st.bytesTotal > 0) (st.bytesDone.toFloat() / st.bytesTotal).coerceIn(0f, 1f) else 0f
            DownloadProgress(frac)
            SceneText(
                if (st.bytesTotal > 0) "${mb(st.bytesDone)} / ${mb(st.bytesTotal)} MB" else "下载中",
                style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1,
            )
        }
        is ModelState.Failed -> SceneText("${mb(pack.totalBytes)} MB · 下载失败", style = SceneTheme.type.footnote, color = c.destructive, maxLines = 1)
        is ModelState.NotInstalled -> SceneText("${mb(pack.totalBytes)} MB", style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1)
    }
}

/** 尾随按钮：下载 / 删除 / 重试；下载中不显示。 */
@Composable
private fun PackAction(st: ModelState, onInstall: () -> Unit, onDelete: () -> Unit) {
    when (st) {
        is ModelState.Installed -> SceneButton("删除", onClick = onDelete, style = ButtonStyle.Destructive, height = SceneSize.glassButton)
        is ModelState.Downloading -> Unit
        is ModelState.Failed -> SceneButton("重试", onClick = onInstall, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
        is ModelState.NotInstalled -> SceneButton("下载", onClick = onInstall, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
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
