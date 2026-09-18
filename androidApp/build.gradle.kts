import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
}

android {
    namespace = "dev.scenenote.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.scenenote.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }   // sherpa-onnx AAR 含 4 ABI（≈ 50 MB）；只保留 arm64（05 篇包体门禁）
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // sherpa-onnx AAR 与 onnxruntime-android AAR 各带一份 libonnxruntime.so（同为 1.28.2、sha256 相同），任取其一
        jniLibs { pickFirsts += "**/libonnxruntime.so" }
    }
    // release 签名凭据放在仓库外（~/.gradle/gradle.properties），缺失时退回未签名产物
    val releaseStorePath = providers.gradleProperty("SCENENOTE_RELEASE_STORE_FILE").orNull
        ?.takeIf { file(it).exists() }
    signingConfigs {
        if (releaseStorePath != null) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = providers.gradleProperty("SCENENOTE_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("SCENENOTE_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("SCENENOTE_RELEASE_KEY_PASSWORD").get()
                enableV3Signing = true   // AGP 默认只开 v2；v3 支持后续密钥轮换
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
