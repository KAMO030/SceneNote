package dev.scenenote.di

import dev.scenenote.audio.AndroidAudioFactory
import dev.scenenote.audio.AudioFactory
import dev.scenenote.core.db.AndroidDriverFactory
import dev.scenenote.core.db.DbDriverFactory
import dev.scenenote.core.platform.AndroidAppPaths
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.core.settings.AndroidSecureStore
import dev.scenenote.core.settings.SecureStore
import dev.scenenote.tts.AndroidSystemTtsProvider
import dev.scenenote.tts.SystemTtsProvider
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

fun androidPlatformModule(): Module = module {
    single<DbDriverFactory> { AndroidDriverFactory(androidContext()) }
    single<SecureStore> { AndroidSecureStore(androidContext()) }
    single<AudioFactory> { AndroidAudioFactory(androidContext()) }
    single<AppPaths> { AndroidAppPaths(androidContext()) }
    // I4 平台能力：先注册占位，姿态 / 热 / 媒体键 / 常亮由平台实现替换
    single<dev.scenenote.core.platform.PostureSensor> { dev.scenenote.core.platform.AndroidPostureSensor(androidContext()) }
    single<dev.scenenote.core.platform.ThermalMonitor> { dev.scenenote.core.platform.AndroidThermalMonitor(androidContext()) }
    single<dev.scenenote.core.platform.MediaKeys> { dev.scenenote.core.platform.AndroidMediaKeys(androidContext()) }
    // createdAtStart：在任何 Activity 创建前注册生命周期回调，否则首个 Activity 的 resume 会漏掉
    single<dev.scenenote.core.platform.ScreenKeeper>(createdAtStart = true) { dev.scenenote.core.platform.AndroidScreenKeeper(androidApplication()) }
    single<SystemTtsProvider> { AndroidSystemTtsProvider(androidContext()) }
}
