package dev.scenenote.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.ModeSpecs
import dev.scenenote.core.model.ScenePreset
import dev.scenenote.core.model.Scenes

/** 首页 = 场景卡（01 篇 §1.3）：旗舰卡置顶，一按即进。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenScene: (String) -> Unit, onOpenSettings: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("场记") }, actions = { TextButton(onClick = onOpenSettings) { Text("设置") } })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(Scenes.mvp, key = { it.id }) { scene -> SceneCard(scene, onClick = { onOpenScene(scene.id) }) }
            item { Spacer(Modifier.height(24.dp)); Text("一按就进对的场景；旗舰是实时翻译。所有识别可离线，云端只用你自己的 Key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SceneCard(scene: ScenePreset, onClick: () -> Unit) {
    val flagship = scene.isLive || scene.isScreenIn
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (flagship) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(scene.name, style = MaterialTheme.typography.titleLarge)
                scene.liveModeId?.let { Text(it + " · " + ModeSpecs.byId(it).name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                scene.screenModeId?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            }
            Text(scene.tagline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                scene.langChips.filter { it.shownOnCard || it.default }.take(2).forEach { chip ->
                    AssistChip(onClick = onClick, label = { Text(Lang.displayName(chip.tag)) })
                }
            }
        }
    }
}
