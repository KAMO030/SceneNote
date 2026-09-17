package dev.scenenote.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.viewmodel.koinViewModel

/**
 * 面对面会话（M0 ⇄ M1 ⇄ M3 同一会话、同一个 VM，规格 §3.1 升级阶梯）：按 ui.mode 切换布局，不重启音频。
 * 三个布局共用同一 NavBackStackEntry 的 LiveViewModel（koinViewModel 默认 owner）。
 */
@Composable
fun LiveConversationScreen(
    sceneId: String, onBack: () -> Unit, onOpenModels: () -> Unit, autostart: Boolean, otherLang: String, myLang: String, feed: String,
    onOpenQuickPhrase: () -> Unit,
    vm: LiveViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { vm.enter(sceneId, myLang, otherLang, feed, autostart) }
    val ui by vm.ui.collectAsState()
    when (ui.mode?.id) {
        "M1" -> LiveM1Screen(vm = vm, onBack = onBack, onOpenModels = onOpenModels)
        "M3" -> LiveM3Screen(vm = vm, onBack = onBack, onOpenModels = onOpenModels)
        else -> LiveM0Screen(sceneId = sceneId, onBack = onBack, onOpenModels = onOpenModels, autostart = autostart, otherLang = otherLang, myLang = myLang, feed = feed, onOpenQuickPhrase = onOpenQuickPhrase, vm = vm)
    }
}
