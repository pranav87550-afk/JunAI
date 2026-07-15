plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.junai.app"
    compileSdk = 35

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
    // IMPORTANT: without this, AAPT may compress the .task model file when
    // packaging the APK. MediaPipe's LLM Inference API mmaps the file
    // directly off disk at its packaged offset — a compressed/re-encoded
    // copy breaks that and fails to load at runtime with a confusing
    // error, not an obviously-model-related one. This must stay in place
    // for as long as functiongemma-270M-it.task lives in assets/.
    androidResources {
        noCompress += "task"
        noCompress += "litertlm"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media:media:1.7.0")
    // For ModelDownloadWorker — survives app-close/Doze while a
    // multi-hundred-MB model download is in progress, with built-in
    // retry. Plain coroutines/Thread would die if the user backgrounds
    // the app mid-download.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // For ModelStateStore's live status (MediatorLiveData combining
    // WorkManager job state + on-disk file check) — ModelManagerActivity
    // (next piece) observes this to drive the Models screen UI.
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    // BUGFIX: bumped from 2.6.1 — that version hits a documented KSP2
    // bug ("unexpected jvm signature V") on suspend DAO methods that
    // return Unit, once Kotlin/KSP were bumped to 2.x for
    // litertlm-android. Fixed upstream in Room 2.7.0-alpha11+.
    implementation("androidx.room:room-runtime:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    // MediaPipe Text Embedder — used by ml/EmbeddingEngine.kt for semantic
    // similarity in TriggerMatcher/PassiveIntentMatcher. See EmbeddingEngine.kt
    // header comment for the fallback plan if this task-level API turns out
    // to be incompatible with EmbeddingGemma's raw exported .tflite.
    implementation("com.google.mediapipe:tasks-text:0.10.21")
    // MediaPipe LLM Inference — used by ml/FunctionCallEngine.kt to run
    // FunctionGemma 270M (assets/functiongemma-270M-it.task). Separate
    // artifact from tasks-text above — genai (LLM) vs text (embedder) are
    // different MediaPipe task libraries.
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    // LiteRT-LM — separate runtime from MediaPipe above, used by
    // ml/ChatEngine.kt to run Qwen3 (assets/qwen3_0_6b_mixed_int4.litertlm).
    // Pinned to a known-working version rather than latest.release, since
    // an unpinned build could silently pick up a breaking API change.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")
}
