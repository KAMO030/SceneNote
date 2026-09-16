package dev.scenenote.di

import com.russhwolf.settings.Settings
import dev.scenenote.asr.DefaultEngineSelector
import dev.scenenote.asr.EngineSelector
import dev.scenenote.audio.AudioFactory
import dev.scenenote.core.db.DbDriverFactory
import dev.scenenote.core.db.SqlLedger
import dev.scenenote.core.db.createDatabase
import dev.scenenote.core.egress.ConsentRegistry
import dev.scenenote.core.egress.EgressGate
import dev.scenenote.core.egress.Ledger
import dev.scenenote.core.egress.SpendGate
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.core.settings.KeyWallet
import dev.scenenote.core.settings.SecureStore
import dev.scenenote.db.SceneNoteDb
import dev.scenenote.live.DefaultLiveSessionMachine
import dev.scenenote.live.LiveSessionMachine
import dev.scenenote.ui.live.LiveViewModel
import dev.scenenote.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/** 平台模块：DbDriverFactory / SecureStore / AudioFactory（Android 需要 Context，见 androidMain）。 */
val commonModule: Module = module {
    single { Settings() }
    single { AppSettings(get()) }
    single { KeyWallet(get<SecureStore>()) }
    single<SceneNoteDb> { createDatabase(get<DbDriverFactory>()) }
    single<Ledger> { SqlLedger(get()) }
    single { ConsentRegistry() }
    single { SpendGate(get(), get()) }
    single { EgressGate(get(), get(), get(), get(), EgressGate.defaultHttpClient()) }
    single<EngineSelector> { DefaultEngineSelector() }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<LiveSessionMachine> {
        val audio = get<AudioFactory>()
        DefaultLiveSessionMachine(audio.routeManager(), audio.sink(), audio.haptics(), get())
    }
    viewModel { LiveViewModel(get(), get<AudioFactory>(), get()) }
    viewModel { SettingsViewModel(get(), get()) }
}

fun initKoin(platformModule: Module, config: KoinAppDeclaration? = null): KoinApplication = startKoin {
    config?.invoke(this)
    modules(platformModule, commonModule)
}
