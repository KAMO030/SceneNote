package dev.scenenote.screen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 画中画状态（Android MainActivity.onPictureInPictureModeChanged 更新；iOS 恒 false）。 */
object PipState {
    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip
    fun set(v: Boolean) { _inPip.value = v }
}
