package dev.scenenote.di

import dev.scenenote.audio.AndroidAudioFactory
import dev.scenenote.audio.AudioFactory
import dev.scenenote.core.db.AndroidDriverFactory
import dev.scenenote.core.db.DbDriverFactory
import dev.scenenote.core.platform.AndroidAppPaths
import dev.scenenote.core.platform.AndroidPngEncoder
import dev.scenenote.core.platform.AndroidSharer
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.core.platform.PngEncoder
import dev.scenenote.core.platform.Sharer
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
    // I5：系统分享面板 + 卡片 PNG 编码
    single<Sharer> { AndroidSharer(androidContext()) }
    single<PngEncoder> { AndroidPngEncoder() }
    // I6 屏内：相册 / 文件选择（ActivityResult 经 PickerBridge）、MediaCodec 抽音频、MediaPlayer + SurfaceView 播放器、
    // MediaProjection + AudioPlaybackCapture 系统音频抓取（前台服务 androidApp/CaptureService）
    single<dev.scenenote.screen.MediaPicker> { dev.scenenote.screen.AndroidMediaPicker(androidContext()) }
    single<dev.scenenote.screen.AudioExtractor> { dev.scenenote.screen.AndroidAudioExtractor(androidContext()) }
    single<dev.scenenote.screen.VideoPlayerFactory> { dev.scenenote.screen.AndroidVideoPlayerFactory(androidContext()) }
    single<dev.scenenote.screen.SystemAudioCapture> { dev.scenenote.screen.AndroidSystemAudioCapture(androidContext()) }
    single<dev.scenenote.core.platform.Notifier> { dev.scenenote.core.platform.AndroidNotifier(androidContext()) }
    single<SystemTtsProvider> { AndroidSystemTtsProvider(androidContext()) }
}
