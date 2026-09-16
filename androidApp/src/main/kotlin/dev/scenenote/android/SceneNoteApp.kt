package dev.scenenote.android

import android.app.Application
import dev.scenenote.di.androidPlatformModule
import dev.scenenote.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class SceneNoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(androidPlatformModule()) {
            androidLogger()
            androidContext(this@SceneNoteApp)
        }
    }
}
