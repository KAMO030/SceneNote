package dev.scenenote.di

import dev.scenenote.audio.AudioFactory
import dev.scenenote.audio.IosAudioFactory
import dev.scenenote.core.db.DbDriverFactory
import dev.scenenote.core.db.IosDriverFactory
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.core.platform.IosAppPaths
import dev.scenenote.core.platform.IosMediaKeys
import dev.scenenote.core.platform.IosPngEncoder
import dev.scenenote.core.platform.IosPostureSensor
import dev.scenenote.core.platform.IosScreenKeeper
import dev.scenenote.core.platform.IosSharer
import dev.scenenote.core.platform.IosThermalMonitor
import dev.scenenote.core.platform.MediaKeys
import dev.scenenote.core.platform.PngEncoder
import dev.scenenote.core.platform.PostureSensor
import dev.scenenote.core.platform.ScreenKeeper
import dev.scenenote.core.platform.Sharer
import dev.scenenote.core.platform.ThermalMonitor
import dev.scenenote.core.settings.KeychainSecureStore
import dev.scenenote.core.settings.SecureStore
import dev.scenenote.tts.IosSystemTtsProvider
import dev.scenenote.tts.SystemTtsProvider
import org.koin.core.module.Module
import org.koin.dsl.module

fun iosPlatformModule(): Module = module {
    single<DbDriverFactory> { IosDriverFactory() }
    single<SecureStore> { KeychainSecureStore() }
    single<AudioFactory> { IosAudioFactory() }
    single<AppPaths> { IosAppPaths() }
    // 平台能力：姿态（CoreMotion + 近距离）/ 热与电量（ProcessInfo + UIDevice）/ 媒体键（MPRemoteCommandCenter）/ 常亮最亮（UIKit）
    single<PostureSensor> { IosPostureSensor() }
    single<ThermalMonitor> { IosThermalMonitor() }
    single<MediaKeys> { IosMediaKeys() }
    single<ScreenKeeper> { IosScreenKeeper() }
    // I5：系统分享面板 + 卡片 PNG 编码
    single<Sharer> { IosSharer() }
    single<PngEncoder> { IosPngEncoder() }
    // I6 屏内：PHPicker / 文件选择、AVAssetReader 抽音频、AVPlayer 播放器；系统音频抓取 iOS 无 App 内路径（ReplayKit 实验层 v1.1），保持 Noop
    single<dev.scenenote.screen.MediaPicker> { dev.scenenote.screen.IosMediaPicker() }
    single<dev.scenenote.screen.AudioExtractor> { dev.scenenote.screen.IosAudioExtractor() }
    single<dev.scenenote.screen.VideoPlayerFactory> { dev.scenenote.screen.IosVideoPlayerFactory() }
    single<dev.scenenote.screen.SystemAudioCapture> { dev.scenenote.screen.NoopSystemAudioCapture() }
    single<dev.scenenote.core.platform.Notifier> { dev.scenenote.core.platform.IosNotifier() }
    single<SystemTtsProvider> { IosSystemTtsProvider() }
}

/** Swift 侧调用：KoinIosKt.startKoinIos() */
fun startKoinIos() { initKoin(iosPlatformModule()) }
