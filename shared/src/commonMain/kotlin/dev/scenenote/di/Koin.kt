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
import dev.scenenote.ui.selftest.AudioSelfTestViewModel
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.models.ModelStore
import dev.scenenote.asr.LocalEngineState
import dev.scenenote.asr.SherpaAsrEngine
import dev.scenenote.bench.AsrBench
import dev.scenenote.bench.LatencyProbe
import dev.scenenote.ui.models.ModelsViewModel
import dev.scenenote.ui.settings.SettingsViewModel
import dev.scenenote.audio.AudioSink
import dev.scenenote.live.FastPath
import dev.scenenote.live.PlaybackQueue
import dev.scenenote.translate.BailianMtTranslator
import dev.scenenote.translate.FastTranslator
import dev.scenenote.translate.KeyTester
import dev.scenenote.translate.Translator
import dev.scenenote.tts.SherpaTts
import dev.scenenote.tts.SystemTtsProvider
import dev.scenenote.tts.TtsRouter
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
    single { ModelStore(get<AppPaths>(), get()) }
    single { LatencyProbe { line -> val p = get<AppPaths>(); p.ensureDir(p.benchDir); p.appendText(p.join(p.benchDir, "latency.jsonl"), line + "\n") } }
    single { SherpaAsrEngine(get(), get()) }
    single { AsrBench(get(), get()) }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    /** 实时会话唯一的输出通道：状态机（设备切换时 stop/flush）与播放队列共用同一个 sink。 */
    single<AudioSink> { get<AudioFactory>().sink() }
    single<LiveSessionMachine> {
        val audio = get<AudioFactory>()
        val engine = get<SherpaAsrEngine>()
        DefaultLiveSessionMachine(audio.routeManager(), get<AudioSink>(), audio.haptics(), get(), arm = { engine.state.value is LocalEngineState.Ready || engine.load() is LocalEngineState.Ready })   // 计划由 LiveViewModel 按场景先选好
    }
    // I3 快路径：翻译（百炼 BYOK；端侧 NMT 待 core/nmt）→ TTS（系统 / sherpa）→ 播放队列
    single { BailianMtTranslator(get(), get(), get()) }
    single { KeyTester(get(), get()) }
    single {
        val bailian = get<BailianMtTranslator>()
        FastTranslator(cloud = { bailian.takeIf { it.hasKey() } }, local = { null as Translator? })
    }
    single { SherpaTts(get()) }
    single { TtsRouter(system = { get<SystemTtsProvider>().get() }, local = { get<SherpaTts>() }) }
    single { PlaybackQueue(get<AudioSink>(), get()) }
    single { FastPath(get(), get(), get(), get<AudioSink>(), get(), get()) }
    viewModel { LiveViewModel(get(), get<AudioFactory>(), get<AppPaths>(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // I7：术语表 / 通知 / 场景编辑 / 账本
    single { dev.scenenote.core.db.GlossaryRepository(get()) }
    single { dev.scenenote.core.scene.SceneStore(get()) }
    viewModel { dev.scenenote.ui.ledger.LedgerViewModel(get(), get()) }
    viewModel { dev.scenenote.ui.glossary.GlossaryViewModel(get(), get()) }
    // I5：资料库 / 会议 / 慢路径 / 导出
    single { dev.scenenote.core.db.SessionRepository(get()) }
    single { dev.scenenote.polish.BailianLlm(get(), get(), get()) }
    single { dev.scenenote.polish.SlowPath(get(), get()) }
    single { dev.scenenote.polish.Exports(get(), get<AppPaths>()) }
    single { dev.scenenote.meeting.MeetingRecorder(get<AudioFactory>(), get(), get(), get<AppPaths>(), get()) }
    viewModel { dev.scenenote.ui.meeting.MeetingViewModel(get(), get(), get()) }
    viewModel { dev.scenenote.ui.note.NoteViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { dev.scenenote.ui.library.LibraryViewModel(get(), get<AppPaths>()) }
    // I6：屏内字幕
    single { dev.scenenote.screen.SubtitleJob(get(), get(), get(), get(), get<AppPaths>(), get(), get()) }
    viewModel { dev.scenenote.ui.screen.ScreenViewModel(get(), get(), get(), get(), get(), get(), get()) }
    single { dev.scenenote.screen.SystemCaption(get(), get(), get(), get()) }
    viewModel { dev.scenenote.ui.screen.SystemCaptionViewModel(get(), get()) }
    viewModel { ModelsViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get()) }
    viewModel { AudioSelfTestViewModel(get<AudioFactory>(), get<AppPaths>(), get(), get(), get(), get(), get(), get()) }
}

fun initKoin(platformModule: Module, config: KoinAppDeclaration? = null): KoinApplication = startKoin {
    config?.invoke(this)
    modules(platformModule, commonModule)
}
