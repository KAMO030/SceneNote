package dev.scenenote.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * 面对面会话（M0 ⇄ M1 ⇄ M3 同一会话、同一个 VM，规格 §3.1 升级阶梯）：按 ui.mode 切换布局，不重启音频。
 * 三个布局共用同一 NavBackStackEntry 的 LiveViewModel（koinViewModel 默认 owner）。
 */
@Composable
fun LiveConversationScreen(
    sceneId: String, onBack: () -> Unit, onOpenModels: (List<String>) -> Unit, autostart: Boolean, otherLang: String, myLang: String, feed: String,
    onOpenQuickPhrase: () -> Unit,
    initialMode: String = "",
    /** 结束会话后的去向：有落库的会话 → 对话卡片页，否则返回。 */
    onEnded: (savedSessionId: String?) -> Unit = { onBack() },
    vm: LiveViewModel = koinViewModel(),
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val finish: () -> Unit = { scope.launch { val id = vm.endAndPersist(); onEnded(id) } }
    LaunchedEffect(Unit) { vm.enter(sceneId, myLang, otherLang, feed, autostart, initialMode = initialMode) }
    val ui by vm.ui.collectAsState()
    when (ui.mode?.id) {
        "M1" -> LiveM1Screen(vm = vm, onBack = finish, onOpenModels = onOpenModels)
        "M3" -> LiveM3Screen(vm = vm, onBack = finish, onOpenModels = onOpenModels)
        else -> LiveM0Screen(sceneId = sceneId, onBack = finish, onOpenModels = onOpenModels, autostart = autostart, otherLang = otherLang, myLang = myLang, feed = feed, onOpenQuickPhrase = onOpenQuickPhrase, vm = vm)
    }
}
