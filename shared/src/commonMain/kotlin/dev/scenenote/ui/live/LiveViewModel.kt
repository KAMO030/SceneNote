package dev.scenenote.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scenenote.audio.AudioFactory
import dev.scenenote.audio.RouteState
import dev.scenenote.core.model.LiveState
import dev.scenenote.core.model.ModeSpecs
import dev.scenenote.core.model.ScenePreset
import dev.scenenote.core.model.Scenes
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.live.LiveSessionMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

data class LiveUiState(
    val scene: ScenePreset? = null,
    val state: LiveState = LiveState.Idle,
    val route: RouteState? = null,
    val myLang: String = "zh-CN",
    val otherLang: String = "en",
    val hint: String = "",
)

class LiveViewModel(
    private val machine: LiveSessionMachine,
    audio: AudioFactory,
    private val settings: AppSettings,
) : ViewModel() {
    private val routeManager = audio.routeManager()
    private val _ui = MutableStateFlow(LiveUiState(myLang = settings.myLang, otherLang = settings.otherLang))
    val ui: StateFlow<LiveUiState> = _ui.asStateFlow()

    init {
        machine.state.onEach { s -> _ui.value = _ui.value.copy(state = s, hint = hintFor(s)) }.launchIn(viewModelScope)
        routeManager.current.onEach { r -> _ui.value = _ui.value.copy(route = r) }.launchIn(viewModelScope)
    }

    fun load(sceneId: String) { _ui.value = _ui.value.copy(scene = Scenes.byId(sceneId)) }
    fun trigger() = machine.trigger()
    fun end() = machine.end()

    private fun hintFor(s: LiveState): String = when (s) {
        LiveState.Idle -> "按一下开始；戴上 A2DP 耳机后手机可以留在口袋"
        LiveState.Arming -> "正在准备：加载模型、锁定路由（内置麦 + 耳机）"
        is LiveState.Live -> "正在听 · ${s.play} · ${s.dir}"
        is LiveState.Paused -> when (s.reason) { "earbud_lost" -> "耳机断开：已停止播放，不会外放。重新连接后自动继续"; "call" -> "来电 / 系统打断，稍后继续"; else -> "已暂停" }
        LiveState.NeedForeground -> "预热已失效，解锁并点一下继续"
        LiveState.Degraded -> "未检测到 A2DP 耳机：可改用双屏对话（M3）"
        LiveState.Ending -> "正在整理会话…"
    }

    override fun onCleared() { machine.end() }

    val modeName: String get() = _ui.value.scene?.liveModeId?.let { ModeSpecs.byId(it).name } ?: ""
}
