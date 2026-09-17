package dev.scenenote.core.designsystem

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * 设计系统文本：默认取 [LocalTextStyle] + [LocalContentColor]。
 * 用法：`SceneText("标题", style = SceneTheme.type.title2)`。
 */
@Composable
fun SceneText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    val resolved = if (color.isSpecified) color else LocalContentColor.current
    val merged = style.merge(color = resolved, textAlign = textAlign ?: style.textAlign)
    BasicText(text = text, modifier = modifier, style = merged, maxLines = maxLines, overflow = overflow, softWrap = softWrap)
}
