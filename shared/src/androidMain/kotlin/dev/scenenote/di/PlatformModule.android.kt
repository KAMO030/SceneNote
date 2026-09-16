package dev.scenenote.di

import dev.scenenote.audio.AndroidAudioFactory
import dev.scenenote.audio.AudioFactory
import dev.scenenote.core.db.AndroidDriverFactory
import dev.scenenote.core.db.DbDriverFactory
import dev.scenenote.core.settings.AndroidSecureStore
import dev.scenenote.core.settings.SecureStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

fun androidPlatformModule(): Module = module {
    single<DbDriverFactory> { AndroidDriverFactory(androidContext()) }
    single<SecureStore> { AndroidSecureStore(androidContext()) }
    single<AudioFactory> { AndroidAudioFactory(androidContext()) }
}
