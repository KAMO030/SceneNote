package dev.scenenote.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.LiveState
import dev.scenenote.core.model.ModeSpecs
import org.koin.compose.viewmodel.koinViewModel

/** 实时页（I0：状态机 + 路由铁律可视化；I3 起接快路径字幕与 TTS）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(sceneId: String, onBack: () -> Unit, vm: LiveViewModel = koinViewModel()) {
    LaunchedEffect(sceneId) { vm.load(sceneId) }
    val ui by vm.ui.collectAsState()
    val scene = ui.scene
    val mode = scene?.liveModeId?.let { ModeSpecs.byId(it) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(scene?.name ?: "实时翻译") }, navigationIcon = { TextButton(onClick = { vm.end(); onBack() }) { Text("返回") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (mode != null) {
                Text("${mode.id} ${mode.name} · ${mode.stage}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(mode.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (scene != null) {
                Text(scene.tagline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("状态", style = MaterialTheme.typography.labelMedium)
                    Text(stateLabel(ui.state), style = MaterialTheme.typography.headlineSmall)
                    Text(ui.hint, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("音频路由铁律", style = MaterialTheme.typography.labelMedium)
                    val r = ui.route
                    if (r == null) Text("未初始化") else {
                        Text("输入：${r.input}   输出：${r.output}")
                        Text(if (r.obeysHeadsetRule) "✓ 内置麦 + A2DP 耳机" else "耳机模式未就绪（${r.note ?: "无 A2DP 耳机"}）",
                            color = if (r.obeysHeadsetRule) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                    }
                }
            }

            Text("${Lang.displayName(ui.myLang)} ⇄ ${Lang.displayName(ui.otherLang)}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::trigger, enabled = ui.state !is LiveState.Live && ui.state != LiveState.Arming) { Text(if (ui.state is LiveState.Paused) "继续" else "开始") }
                OutlinedButton(onClick = vm::end, enabled = ui.state != LiveState.Idle) { Text("结束") }
            }
        }
    }
}

private fun stateLabel(s: LiveState): String = when (s) {
    LiveState.Idle -> "待机"; LiveState.Arming -> "准备中"; is LiveState.Live -> "进行中"; is LiveState.Paused -> "已暂停"
    LiveState.NeedForeground -> "需要前台"; LiveState.Degraded -> "降级"; LiveState.Ending -> "结束中"
}
