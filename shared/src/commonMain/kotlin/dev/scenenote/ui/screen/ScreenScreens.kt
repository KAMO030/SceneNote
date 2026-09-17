package dev.scenenote.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.screen.MediaSource
import org.koin.compose.viewmodel.koinViewModel

/** 占位：I6 按原型 ScreenIntake.dc.html 重做（相册 / 文件 / 直链 / 已处理列表）。 */
@Composable
fun ScreenIntakeScreen(onBack: () -> Unit, onOpenPlayer: () -> Unit, vm: ScreenViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsState()
    Column(Modifier.fillMaxSize().padding(top = 104.dp, bottom = 140.dp, start = 16.dp, end = 16.dp)) {
        SceneText("视频字幕（占位） ${ui.job.phase}")
        SceneButton(text = "相册", onClick = { vm.pick(MediaSource.GALLERY); onOpenPlayer() }, style = ButtonStyle.Prominent)
        SceneButton(text = "文件", onClick = { vm.pick(MediaSource.FILE); onOpenPlayer() }, style = ButtonStyle.Gray)
        SceneButton(text = "返回", onClick = onBack, style = ButtonStyle.Gray)
    }
}

/** 占位：I6 按原型 ScreenS4.dc.html 重做（常驻深色播放器、双进度条、等待遮罩、cue 列表、导出）。 */
@Composable
fun ScreenPlayerScreen(onBack: () -> Unit, vm: ScreenViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsState()
    Column(Modifier.fillMaxSize().padding(top = 104.dp, bottom = 140.dp, start = 16.dp, end = 16.dp)) {
        SceneText("播放器（占位） ${ui.job.phase} ${ui.job.cues.size} 句")
        ui.currentCue?.let { SceneText(it.translation ?: it.text) }
        SceneButton(text = "导出 SRT", onClick = { vm.exportSrt() }, style = ButtonStyle.Prominent)
        SceneButton(text = "返回", onClick = onBack, style = ButtonStyle.Gray)
    }
}

/** 屏内流程：同一个 VM，未选视频 → 入口页；已开始 → 播放器页（避免两条路由各自一个 VM）。 */
@Composable
fun ScreenFlowScreen(onBack: () -> Unit, reopenSessionId: String = "", vm: ScreenViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsState()
    androidx.compose.runtime.LaunchedEffect(reopenSessionId) { if (reopenSessionId.isNotBlank()) vm.reopen(reopenSessionId) }
    val inPlayer = ui.job.media != null || ui.job.cues.isNotEmpty() || ui.downloading != null
    if (inPlayer) ScreenPlayerScreen(onBack = { vm.cancel(); }, vm = vm) else ScreenIntakeScreen(onBack = onBack, onOpenPlayer = {}, vm = vm)
}
