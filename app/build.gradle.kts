plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.junai.app"
    // BUGFIX: bumped from 35 — llamacpp-kotlin:0.4.0's transitive
    // androidx.core-ktx:1.18.0 dependency requires compileSdk 36+.
    // targetSdk left at 35 on purpose — compileSdk just controls which
    // APIs are available to compile against, targetSdk is a separate,
    // bigger decision (opts into new runtime behavior) for later.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.junai.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Model files (.task/.litertlm) used to live in assets/ and needed
    // noCompress here so AAPT wouldn't corrupt them for MediaPipe's mmap
    // loading. They're downloaded to filesDir/models/ at runtime now
    // (see ModelCatalog/ModelDownloadManager) instead, so this isn't
    // needed anymore — kept as an empty block as a landmark in case that
    // ever changes back.
    androidResources {

}
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.room:room-runtime:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.google.mediapipe:tasks-text:0.10.21")
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")
    // llama.cpp/GGUF migration (Piece 1) — kotlinllamacpp, published on
    // Maven Central so it resolves like litertlm-android above: no local
    // NDK/CMake build needed, prebuilt native binaries ship in the AAR.
    // Intended to run ALONGSIDE ChatEngine.kt (LiteRT-LM) for A/B
    // comparison via a new GGUFChatEngine.kt — not a replacement yet.
    implementation("io.github.ljcamargo:llamacpp-kotlin:0.4.0")
}
