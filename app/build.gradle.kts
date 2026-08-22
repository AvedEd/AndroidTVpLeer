plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.torrplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.torrplayer"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    // ===== НАСТРОЙКА ПОДПИСИ =====
    signingConfigs {
        create("release") {
            // Значения приходят из переменных окружения, которые задаёт CI
            // (см. блок env: в .github/workflows/build-apk.yml).
            // Если переменная не задана - сборка упадёт с понятной ошибкой,
            // а не соберётся тихо с неправильным ключом.
            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: error("KEYSTORE_PASSWORD is not set")
            keyAlias = System.getenv("KEY_ALIAS")
                ?: error("KEY_ALIAS is not set")
            keyPassword = System.getenv("KEY_PASSWORD")
                ?: error("KEY_PASSWORD is not set")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.multidex:multidex:2.0.1")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
