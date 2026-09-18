package dev.scenenote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scenenote.core.model.PrivacyMode
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.core.settings.KeyWallet
import dev.scenenote.core.settings.Providers
import dev.scenenote.core.model.Lang
import dev.scenenote.models.ModelStore
import dev.scenenote.nmt.NmtRoutes
import dev.scenenote.translate.KeyTestResult
import dev.scenenote.translate.KeyTester
import dev.scenenote.tts.TtsPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dev.scenenote.shared.resources.*
import org.jetbrains.compose.resources.StringResource

data class ProviderRow(val id: String, val name: StringResource, val masked: String?, val baseUrl: String?, val needsBaseUrl: Boolean, val keyHint: StringResource, val docUrl: String)
data class SettingsUiState(
    val privacy: PrivacyMode, val monthlyLimit: Double, val providers: List<ProviderRow>,
    val myLang: String, val otherLang: String, val ttsPreference: TtsPreference, val mtModel: String,
    /** 翻译档选的是「本机」：只走端侧 NMT，密钥留着不动。[mtLocalReady] = 当前语言对两个方向的离线包都装好了。 */
    val mtLocalOnly: Boolean = false, val mtLocalReady: Boolean = true,
    /** 面对面对话三开关（AppSettings 同名属性）：姿态自动进 M1 / 自动判向（关 = 固定对方 → 我）/ 进 M1 播开场白（−6 dB）。 */
    val autoPosture: Boolean = true, val directionAuto: Boolean = true, val politeOpener: Boolean = false,
    val keyTest: Map<String, KeyTestResult> = emptyMap(), val testing: String? = null,
)

class SettingsViewModel(private val settings: AppSettings, private val wallet: KeyWallet, private val tester: KeyTester, private val store: ModelStore) : ViewModel() {
    private val _ui = MutableStateFlow(snapshot(null))
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    private fun snapshot(): SettingsUiState = snapshot(_ui.value)
    private fun snapshot(keep: SettingsUiState?) = SettingsUiState(
        privacy = settings.privacy.value, monthlyLimit = settings.monthlyLimit,
        providers = Providers.all.map { ProviderRow(it.id, it.name, wallet.masked(it.id), wallet.baseUrl(it.id), it.host.isEmpty(), it.keyHint, it.docUrl) },
        myLang = settings.myLang, otherLang = settings.otherLang, ttsPreference = TtsPreference.of(settings.ttsPreference), mtModel = settings.mtModel,
        mtLocalOnly = settings.mtLocalOnly, mtLocalReady = localMtReady(),
        autoPosture = settings.autoPosture, directionAuto = settings.directionAuto, politeOpener = settings.politeOpener,
        keyTest = keep?.keyTest ?: emptyMap(), testing = keep?.testing,
    )

    /** 其他页（实时 Tab 的语言对卡）直接写了 AppSettings：回到设置 Tab 时重读快照。 */
    fun refresh() { _ui.value = snapshot() }

    fun setPrivacy(mode: PrivacyMode) { settings.setPrivacy(mode); _ui.value = snapshot() }
    fun setKey(providerId: String, key: String) { wallet.setKey(providerId, key); _ui.value = snapshot().copy(keyTest = _ui.value.keyTest - providerId) }
    fun setBaseUrl(providerId: String, url: String) { wallet.setBaseUrl(providerId, url); _ui.value = snapshot() }
    fun setMonthlyLimit(v: Double) { settings.monthlyLimit = v; _ui.value = snapshot() }
    fun setMyLang(v: String) { settings.myLang = v; _ui.value = snapshot() }
    fun setOtherLang(v: String) { settings.otherLang = v; _ui.value = snapshot() }
    fun setTtsPreference(p: TtsPreference) { settings.ttsPreference = p.id; _ui.value = snapshot() }
    /** 翻译档：[model] = null 选的是「本机」；换回云端档时把上次的云端档写回，密钥全程不动。 */
    fun setMtTier(model: String?) {
        settings.mtLocalOnly = model == null
        if (model != null) settings.mtModel = model
        _ui.value = snapshot()
    }

    /**
     * 本机档能不能真的翻：当前语言对来回两个方向的离线包都装了才算。
     * 对方语言设成自动时这里放过——具体语种要等会话里听出来，缺包由实时页的「去下载」提示兜。
     */
    private fun localMtReady(): Boolean {
        val me = settings.myLang
        val other = settings.otherLang
        if (other == Lang.AUTO) return true
        return NmtRoutes.resolve(other, me, store::isInstalled) != null && NmtRoutes.resolve(me, other, store::isInstalled) != null
    }

    // ---- 面对面对话（I4 三开关；实时页在会话中也会直接写 AppSettings，回到设置 Tab 时 refresh 重读）----
    /** 竖起手机（竖直 + 屏幕朝外 + 静止）自动从仅听进耳听·面屏；只在前台亮屏时生效。 */
    fun setAutoPosture(on: Boolean) { settings.autoPosture = on; _ui.value = snapshot() }
    /** 自动判向（输出脚本 + 声纹）；关 = 固定对方 → 我，退化为单工。 */
    fun setDirectionAuto(on: Boolean) { settings.directionAuto = on; _ui.value = snapshot() }
    /** 进入耳听·面屏时用对方语言播一句开场白（−6 dB）；默认关，录音告知默认由礼貌卡完成。 */
    fun setPoliteOpener(on: Boolean) { settings.politeOpener = on; _ui.value = snapshot() }

    /** 测试连接：只对百炼做真实翻译一句（其他厂商 I3 未接）。 */
    fun testKey(providerId: String) {
        if (_ui.value.testing != null) return
        _ui.value = _ui.value.copy(testing = providerId)
        viewModelScope.launch {
            val r = if (providerId == Providers.bailian.id) tester.testBailian() else KeyTestResult(false, "provider not supported yet")
            _ui.value = _ui.value.copy(testing = null, keyTest = _ui.value.keyTest + (providerId to r))
        }
    }
}
