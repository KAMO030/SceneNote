package dev.scenenote.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 壳的覆盖层插槽：Tab 页要把 sheet / 压暗层画到玻璃 Tab 栏之上时，把内容放进来（原型 KeyWallet：sheet 盖住 Tab 栏）。
 * 为 null 表示不在四 Tab 壳里（页面应自行内联渲染）。
 */
val LocalShellOverlay = staticCompositionLocalOf<MutableState<(@Composable BoxScope.() -> Unit)?>?> { null }
