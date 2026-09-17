package dev.scenenote.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.SceneText

/** 占位：资料库列表页在 I5 落地（14 篇 §1 ①）。 */
@Composable
fun LibraryTab() {
    Column(Modifier.fillMaxSize().padding(top = 104.dp, bottom = 140.dp, start = 16.dp, end = 16.dp)) { SceneText("资料库", style = dev.scenenote.core.designsystem.SceneTheme.type.largeTitle); SceneText("稍后开放", color = dev.scenenote.core.designsystem.SceneTheme.colors.secondaryLabel) }
}
