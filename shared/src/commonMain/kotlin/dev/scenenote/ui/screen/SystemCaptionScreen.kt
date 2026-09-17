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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneDock
import dev.scenenote.core.designsystem.SceneIconButton
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.screen.CaptionState
import dev.scenenote.screen.CaptureState
import dev.scenenote.screen.SystemCaption
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.viewmodel.koinViewModel

class SystemCaptionViewModel(private val caption: SystemCaption, private val settings: AppSettings) : ViewModel() {
    val state: StateFlow<CaptionState> = caption.state
    val available: Boolean get() = caption.available
    fun start() = caption.start(settings.otherLang, settings.myLang)
    fun stop() = caption.stop()
    fun pip() = caption.enterPip()
    override fun onCleared() = caption.stop()
}

/**
 * Android 系统字幕（S1）：披露 → 开始（系统弹"开始投屏 / 录制"确认）→ 字幕列表；「悬浮」进画中画只留字幕条。
 * 常驻深色（沉浸式媒体界面）。iOS 上此页不可达（入口按平台隐藏）。
 */
@Composable
fun SystemCaptionScreen(onBack: () -> Unit, vm: SystemCaptionViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    val inPip by dev.scenenote.screen.PipState.inPip.collectAsState()
    SceneTheme(dark = true) {
        val c = SceneTheme.colors
        if (inPip) {
            // 画中画：只有一条字幕
            Box(Modifier.fillMaxSize().background(c.systemBackground).padding(12.dp), contentAlignment = Alignment.Center) {
                val last = s.lines.lastOrNull()
                SceneText(last?.translation ?: last?.text ?: s.partial.ifBlank { "…" }, style = SceneTheme.type.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.label, maxLines = 2)
            }
            return@SceneTheme
        }
        GlassScaffold(
            background = c.systemBackground,
            topBar = { SceneNavBar(title = "系统字幕", onBack = { vm.stop(); onBack() }) },
            bottomBar = {
                SceneDock {
                    SceneIconButton(SceneIcons.Scenes, contentDescription = "悬浮字幕条", onClick = { vm.pip() }, size = 56.dp, enabled = s.capture == CaptureState.CAPTURING)
                    if (s.capture == CaptureState.CAPTURING) SceneButton("停止", onClick = { vm.stop() }, style = ButtonStyle.Destructive, modifier = Modifier.fillMaxWidth(0.5f))
                    else SceneButton("开始", onClick = { vm.start() }, style = ButtonStyle.Prominent, modifier = Modifier.fillMaxWidth(0.5f))
                    SceneIconButton(SceneIcons.Close, contentDescription = "关闭", onClick = { vm.stop(); onBack() }, size = 56.dp)
                }
            },
        ) {
            val listState = rememberLazyListState()
            LaunchedEffect(s.lines.size) { if (s.lines.isNotEmpty()) listState.animateScrollToItem(s.lines.size - 1) }
            Column(Modifier.fillMaxSize().padding(top = 104.dp, bottom = 140.dp, start = SceneSpacing.page, end = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s.capture) {
                    CaptureState.IDLE -> SceneText("开始后回到视频 App，这里出字幕；抓的是声音，不截屏。部分 App 不允许抓取。", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
                    CaptureState.REQUESTING -> SceneText("等待系统确认…", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
                    CaptureState.BLOCKED -> SceneText("这个 App 不允许抓取声音，换一个试试。", style = SceneTheme.type.subheadline, color = c.destructive)
                    CaptureState.DENIED -> SceneText("没有拿到抓取权限。", style = SceneTheme.type.subheadline, color = c.destructive)
                    CaptureState.ERROR -> SceneText(s.error ?: "已停止，点「开始」重新授权", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
                    CaptureState.CAPTURING -> Row(verticalAlignment = Alignment.CenterVertically) { SceneText("正在听", style = SceneTheme.type.subheadline, color = c.tint) }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(s.lines, key = { it.index }) { cue ->
                        Column {
                            SceneText(cue.translation ?: cue.text, style = SceneTheme.type.title3, color = c.label)
                            if (cue.translation != null) SceneText(cue.text, style = SceneTheme.type.footnote, color = c.secondaryLabel)
                        }
                    }
                    if (s.partial.isNotBlank()) item { SceneText(s.partial, style = SceneTheme.type.body, color = c.tertiaryLabel) }
                }
            }
        }
    }
}
