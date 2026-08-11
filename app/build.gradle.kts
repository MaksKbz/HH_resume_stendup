plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "kz.hh.resumebot"
    compileSdk = 34

    defaultConfig {
        applicationId = "kz.hh.resumebot"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }

    // Фиксированный ключ: все сборки подписываются одинаково,
    // поэтому обновления ставятся поверх без удаления приложения
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
