package dev.scenenote.di

import dev.scenenote.audio.AudioFactory
import dev.scenenote.audio.IosAudioFactory
import dev.scenenote.core.db.DbDriverFactory
import dev.scenenote.core.db.IosDriverFactory
import dev.scenenote.core.settings.KeychainSecureStore
import dev.scenenote.core.settings.SecureStore
import org.koin.core.module.Module
import org.koin.dsl.module

fun iosPlatformModule(): Module = module {
    single<DbDriverFactory> { IosDriverFactory() }
    single<SecureStore> { KeychainSecureStore() }
    single<AudioFactory> { IosAudioFactory() }
}

/** Swift 侧调用：KoinIosKt.startKoinIos() */
fun startKoinIos() { initKoin(iosPlatformModule()) }
