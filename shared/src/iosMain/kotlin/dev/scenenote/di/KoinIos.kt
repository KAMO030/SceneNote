package dev.scenenote.di

import dev.scenenote.audio.AudioFactory
import dev.scenenote.audio.IosAudioFactory
import dev.scenenote.core.db.DbDriverFactory
import dev.scenenote.core.db.IosDriverFactory
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.core.platform.IosAppPaths
import dev.scenenote.core.platform.IosMediaKeys
import dev.scenenote.core.platform.IosPostureSensor
import dev.scenenote.core.platform.IosScreenKeeper
import dev.scenenote.core.platform.IosThermalMonitor
import dev.scenenote.core.platform.MediaKeys
import dev.scenenote.core.platform.PostureSensor
import dev.scenenote.core.platform.ScreenKeeper
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
    single<SystemTtsProvider> { IosSystemTtsProvider() }
}

/** Swift 侧调用：KoinIosKt.startKoinIos() */
fun startKoinIos() { initKoin(iosPlatformModule()) }
