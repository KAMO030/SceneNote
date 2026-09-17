package dev.scenenote.core.designsystem

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * 按压态：Apple 风格的整块压暗 / 提亮，而不是 Material 涟漪（不引入 material-ripple）。
 * 浅色下叠 8% 黑，深色下叠 10% 白；Increase Contrast 时加倍。
 */
class PressHighlightIndication(private val overlay: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressHighlightNode(interactionSource, overlay)
    override fun equals(other: Any?): Boolean = other is PressHighlightIndication && other.overlay == overlay
    override fun hashCode(): Int = overlay.hashCode()

    companion object {
        fun forColors(colors: SceneColors, increaseContrast: Boolean): PressHighlightIndication {
            val base = if (colors.isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
            return PressHighlightIndication(if (increaseContrast) base.copy(alpha = base.alpha * 2f) else base)
        }
    }
}

private class PressHighlightNode(private val interactionSource: InteractionSource, private val overlay: Color) : Modifier.Node(), DrawModifierNode {
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                val next = when (interaction) {
                    is PressInteraction.Press -> true
                    is PressInteraction.Release, is PressInteraction.Cancel -> false
                    else -> pressed
                }
                if (next != pressed) { pressed = next; invalidateDraw() }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) drawRect(overlay)
    }
}
