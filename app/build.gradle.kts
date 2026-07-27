plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.torrplayer"
    compileSdk = 35   // Media3 1.9.x требует компиляцию под API 35 и выше

    defaultConfig {
        applicationId = "com.example.torrplayer"
        minSdk = 24           // Android 7.0 — Media3 1.9.x подняли минимум с 21 до 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Media3 / ExoPlayer — поддержка mp4, mkv/webm, ts, ps, ogg, wav, flac, avi, hls, dash, rtsp и т.д.
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.9.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")
    implementation("androidx.media3:media3-session:1.9.0")

    // FFmpeg-расширение (готовая сборка проекта Jellyfin, обновляется вместе с Media3) —
    // добавляет декодирование AC-3/E-AC-3/DTS/TrueHD, которые стандартный Android не умеет
    // декодировать сам. Ничего в коде менять не нужно: DefaultRenderersFactory уже настроен
    // на EXTENSION_RENDERER_MODE_PREFER и подхватит FfmpegAudioRenderer автоматически, как
    // только он появится в classpath.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    // Сеть — общение с TorrServer HTTP API
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
