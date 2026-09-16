package dev.scenenote.ui.settings

import androidx.lifecycle.ViewModel
import dev.scenenote.core.model.PrivacyMode
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.core.settings.KeyWallet
import dev.scenenote.core.settings.Providers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProviderRow(val id: String, val name: String, val masked: String?, val baseUrl: String?, val needsBaseUrl: Boolean)
data class SettingsUiState(val privacy: PrivacyMode, val monthlyLimit: Double, val providers: List<ProviderRow>)

class SettingsViewModel(private val settings: AppSettings, private val wallet: KeyWallet) : ViewModel() {
    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    private fun snapshot() = SettingsUiState(
        privacy = settings.privacy.value, monthlyLimit = settings.monthlyLimit,
        providers = Providers.all.map { ProviderRow(it.id, it.name, wallet.masked(it.id), wallet.baseUrl(it.id), it.host.isEmpty()) },
    )

    fun setPrivacy(mode: PrivacyMode) { settings.setPrivacy(mode); _ui.value = snapshot() }
    fun setKey(providerId: String, key: String) { wallet.setKey(providerId, key); _ui.value = snapshot() }
    fun setBaseUrl(providerId: String, url: String) { wallet.setBaseUrl(providerId, url); _ui.value = snapshot() }
    fun setMonthlyLimit(v: Double) { settings.monthlyLimit = v; _ui.value = snapshot() }
}
