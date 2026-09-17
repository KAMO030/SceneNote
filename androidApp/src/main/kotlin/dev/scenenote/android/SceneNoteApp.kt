package dev.scenenote.android

import android.app.Application
import dev.scenenote.di.androidPlatformModule
import dev.scenenote.di.initKoin
import dev.scenenote.tts.AndroidSystemTtsProvider
import dev.scenenote.tts.SystemTtsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class SceneNoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = initKoin(androidPlatformModule()) {
            androidLogger()
            androidContext(this@SceneNoteApp)
        }.koin
        // 系统 TTS 引擎探测（无引擎的机型如 vivo V2054A 直接判定不可用，端侧语音包接管）
        (koin.get<SystemTtsProvider>() as? AndroidSystemTtsProvider)?.let { p ->
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { p.init() }
        }
    }
}
