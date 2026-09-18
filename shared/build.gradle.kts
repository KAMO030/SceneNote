import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
        // 让 AVFAudio / AVAudioSession 抛出的 NSException 变成 kotlinx.cinterop.ForeignException，runCatching 才真正有效
        iosTarget.compilations.all { compileTaskProvider.configure { compilerOptions.freeCompilerArgs.add("-Xforeign-exception-mode=objc-wrap") } }
        // sherpa-onnx C API：静态库经 cinterop 嵌入（scripts/fetch-sherpa.sh 拉取）
        val sherpaLibDir = if (iosTarget.name == "iosArm64") "ios-arm64" else "ios-simulator-arm64"
        iosTarget.compilations.getByName("main").cinterops.create("sherpa") {
            defFile(project.file("src/nativeInterop/cinterop/sherpa.def"))
            includeDirs(project.file("native/sherpa/include"))
            extraOpts("-libraryPath", project.file("native/sherpa/$sherpaLibDir").absolutePath)
        }
        // onnxruntime C API（core/nmt 端侧翻译）：只要头文件，静态库已随上面的 sherpa cinterop 嵌入（同一份 1.28.2）
        iosTarget.compilations.getByName("main").cinterops.create("onnxruntime") {
            defFile(project.file("src/nativeInterop/cinterop/onnxruntime.def"))
            includeDirs(project.file("native/onnxruntime/include"))
        }
    }

    android {
        namespace = "dev.scenenote.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        // JVM 单测（commonTest 也在这里跑，比 iOS 模拟器快得多）；core/nmt 的端到端用桌面版 ORT
        withHostTestBuilder {}.configure { isIncludeAndroidResources = false }
    }

    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.time.ExperimentalTime",
            "-opt-in=kotlin.uuid.ExperimentalUuidApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
            "-Xexpect-actual-classes",
        )
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.animation)   // core/design-system：分段控件 / 开关 / sheet 动效
            implementation(libs.haze)                 // 玻璃模糊（功能层取样内容层）
            implementation(libs.haze.materials)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)

            implementation(libs.navigation.compose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.lifecycle.viewmodelCompose)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.okio)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.noarg)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
        }
        androidMain.dependencies {
            implementation(files("libs/sherpa-onnx-1.13.8.aar"))
            implementation(files("libs/onnxruntime-android-1.28.2.aar"))   // core/nmt：Java API + JNI；libonnxruntime.so 与 sherpa AAR 同一份（app 侧 pickFirst）
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.koin.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.onnxruntime.jvm)   // 桌面版 ORT（与 AAR 同一套 ai.onnxruntime Java API，自带 macOS / Linux 原生库）
        }
    }
}

sqldelight {
    databases {
        create("SceneNoteDb") {
            packageName.set("dev.scenenote.db")
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
