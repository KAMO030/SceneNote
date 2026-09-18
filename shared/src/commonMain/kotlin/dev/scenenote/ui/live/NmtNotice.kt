package dev.scenenote.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.i18n.stringResource
import dev.scenenote.shared.resources.Res
import dev.scenenote.shared.resources.live_download
import dev.scenenote.shared.resources.live_nmt_missing
import dev.scenenote.shared.resources.live_nmt_unsupported

/**
 * 零 Key 时离线翻译包的行内提示（四个实时布局共用，不弹窗）：
 * 缺包 → 一句话 + 「去下载」（带上缺的包 id，语音包页把它们排在最前，用户自己点下载）；这对语言没有端侧模型 → 只提示一句。
 * 有云端 Key 时 [LiveUiState.nmtMissing] 为空，什么都不画。
 */
@Composable
internal fun NmtNotice(ui: LiveUiState, onOpenModels: (List<String>) -> Unit, centered: Boolean = false) {
    val c = SceneTheme.colors
    when {
        ui.nmtUnsupported -> SceneText(stringResource(Res.string.live_nmt_unsupported), Modifier.fillMaxWidth(), style = SceneTheme.type.footnote, color = c.secondaryLabel, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
        ui.nmtMissing.isNotEmpty() -> Row(
            Modifier.fillMaxWidth(), horizontalArrangement = if (centered) Arrangement.spacedBy(SceneSpacing.s, Alignment.CenterHorizontally) else Arrangement.spacedBy(SceneSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SceneText(stringResource(Res.string.live_nmt_missing), if (centered) Modifier else Modifier.weight(1f), style = SceneTheme.type.footnote, color = c.destructive)
            SceneButton(stringResource(Res.string.live_download), onClick = { onOpenModels(ui.nmtMissing) }, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
        }
    }
}
