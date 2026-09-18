package dev.scenenote.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneDock
import dev.scenenote.core.designsystem.SceneIconButton
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneSegmentedControl
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.screen.CaptionState
import dev.scenenote.screen.CaptureState
import dev.scenenote.screen.SystemCaption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.compose.viewmodel.koinViewModel
import dev.scenenote.core.i18n.string
import dev.scenenote.shared.resources.*
import dev.scenenote.core.i18n.stringResource

class SystemCaptionViewModel(private val caption: SystemCaption, private val settings: AppSettings) : ViewModel() {
    val state: StateFlow<CaptionState> = caption.state
    val autostart: StateFlow<Boolean> = caption.autostart
    val available: Boolean get() = caption.available
    /** 设定的对方语言 ≠ 我的语言才有「译文 / 双语」可切；同语种时字幕条直接放原文（逐句识别出的外语句子照样会翻，列表里能看到）。 */
    val translating: Boolean get() = settings.otherLang != settings.myLang
    private val _bilingual = MutableStateFlow(settings.captionBilingual)
    val bilingual: StateFlow<Boolean> = _bilingual.asStateFlow()
    fun setBilingual(v: Boolean) { settings.captionBilingual = v; _bilingual.value = v }
    fun start() = caption.start(settings.otherLang, settings.myLang)
    fun consumeAutostart() = caption.consumeAutostart()
    fun stop() = caption.stop()
    fun pip() = caption.enterPip()
    override fun onCleared() = caption.stop()
}

/**
 * Android 系统字幕（S1）：披露 → 开始（系统弹"开始投屏 / 录制"确认）→ 字幕列表；「悬浮」进画中画只留字幕条。
 * 磁贴「屏内翻译」进来带 autostart：不用再点「开始」；抓取开始后切回视频 App 会自动进画中画（[AndroidPip.armAutoEnter]）。
 * 常驻深色（沉浸式媒体界面）。iOS 上此页不可达（入口按平台隐藏）。
 */
@Composable
fun SystemCaptionScreen(onBack: () -> Unit, vm: SystemCaptionViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    val inPip by dev.scenenote.screen.PipState.inPip.collectAsState()
    val autostart by vm.autostart.collectAsState()
    LaunchedEffect(autostart) { if (autostart) { vm.consumeAutostart(); vm.start() } }
    SceneTheme(dark = true) {
        val c = SceneTheme.colors
        val bilingual by vm.bilingual.collectAsState()
        if (inPip) {
            // 画中画只放一句：译文为主，等译文回来之前继续挂着上一句，不拿原文顶位；双语 = 同一句的原文小字在上（字号只许 14 / 18 sp）
            PipCaption(s, translating = vm.translating, bilingual = bilingual)
            return@SceneTheme
        }
        GlassScaffold(
            background = c.systemBackground,
            topBar = { SceneNavBar(title = stringResource(Res.string.screen_syscap_row), onBack = { vm.stop(); onBack() }) },
            bottomBar = {
                SceneDock {
                    SceneIconButton(SceneIcons.Scenes, contentDescription = stringResource(Res.string.syscap_pip), onClick = { vm.pip() }, size = 56.dp, enabled = s.capture == CaptureState.CAPTURING)
                    if (s.capture == CaptureState.CAPTURING) SceneButton(stringResource(Res.string.syscap_stop), onClick = { vm.stop() }, style = ButtonStyle.Destructive, modifier = Modifier.fillMaxWidth(0.5f))
                    else SceneButton(stringResource(Res.string.live_start), onClick = { vm.start() }, style = ButtonStyle.Prominent, modifier = Modifier.fillMaxWidth(0.5f))
                    SceneIconButton(SceneIcons.Close, contentDescription = stringResource(Res.string.common_close), onClick = { vm.stop(); onBack() }, size = 56.dp)
                }
            },
        ) {
            val listState = rememberLazyListState()
            LaunchedEffect(s.lines.size) { if (s.lines.isNotEmpty()) listState.animateScrollToItem(s.lines.size - 1) }
            Column(Modifier.fillMaxSize().padding(top = 104.dp, bottom = 140.dp, start = SceneSpacing.page, end = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.translating) SceneSegmentedControl(
                    options = listOf(stringResource(Res.string.screen_seg_translation), stringResource(Res.string.syscap_seg_bilingual)),
                    selectedIndex = if (bilingual) 1 else 0,
                    onSelect = { vm.setBilingual(it == 1) },
                )
                when (s.capture) {
                    CaptureState.IDLE -> SceneText(stringResource(Res.string.syscap_idle_hint), style = SceneTheme.type.subheadline, color = c.secondaryLabel)
                    CaptureState.REQUESTING -> SceneText(stringResource(Res.string.syscap_requesting), style = SceneTheme.type.subheadline, color = c.secondaryLabel)
                    CaptureState.BLOCKED -> SceneText(stringResource(Res.string.syscap_blocked), style = SceneTheme.type.subheadline, color = c.destructive)
                    CaptureState.DENIED -> SceneText(stringResource(Res.string.syscap_denied), style = SceneTheme.type.subheadline, color = c.destructive)
                    CaptureState.ERROR -> SceneText(s.error?.string() ?: stringResource(Res.string.syscap_stopped), style = SceneTheme.type.subheadline, color = c.secondaryLabel)
                    CaptureState.CAPTURING -> Row(verticalAlignment = Alignment.CenterVertically) { SceneText(stringResource(Res.string.live_hint_listening), style = SceneTheme.type.subheadline, color = c.tint) }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(s.lines, key = { it.index }) { cue ->
                        Column {
                            SceneText(cue.translation ?: cue.text, style = SceneTheme.type.title3, color = c.label)
                            if (cue.translation != null && cue.translation != cue.text) SceneText(cue.text, style = SceneTheme.type.footnote, color = c.secondaryLabel)   // 本来就是我的语言：译文即原文，不重复
                        }
                    }
                    if (s.partial.isNotBlank()) item { SceneText(s.partial, style = SceneTheme.type.body, color = c.tertiaryLabel) }
                }
            }
        }
    }
}

/** 画中画字幕条：黑底白字，18 sp 译文最多 2 行；双语时 14 sp 原文 1 行在上。 */
@Composable
private fun PipCaption(s: CaptionState, translating: Boolean, bilingual: Boolean) {
    val c = SceneTheme.colors
    val main = SceneTheme.type.body.copy(fontSize = 18.sp, lineHeight = 22.sp)
    val small = SceneTheme.type.body.copy(fontSize = 14.sp, lineHeight = 17.sp)
    // 同语种：最新一句原文（识别中的字原位替换）；跨语种：最近一句已经有译文的；译员不可用：退回最新原文，总比空着强
    val cue = if (translating && !s.mtUnavailable) s.lines.lastOrNull { it.translation != null } else s.lines.lastOrNull()
    val text = when {
        !translating -> s.partial.ifBlank { cue?.text.orEmpty() }
        else -> cue?.translation ?: cue?.text.takeIf { s.mtUnavailable }.orEmpty()
    }.ifBlank { "…" }
    Column(Modifier.fillMaxSize().background(c.systemBackground).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
        if (translating && bilingual && cue?.translation != null && cue.translation != cue.text) SceneText(cue.text, style = small, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SceneText(text, style = main, color = c.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
