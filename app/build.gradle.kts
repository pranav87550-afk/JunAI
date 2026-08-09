plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.junai.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.junai.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // BUGFIX (update installs failing: "App not installed as package
    // conflicts with an existing package"): without an explicit
    // signingConfig, Android Gradle Plugin auto-generates a debug
    // keystore using fixed conventions (alias, password, DN) — but the
    // actual cryptographic key material inside it is randomly generated
    // fresh whenever that file doesn't already exist. On a GitHub
    // Actions runner, ~/.android/debug.keystore never persists between
    // builds (each run is a clean VM), so every single build was
    // unknowingly signed with a DIFFERENT key — and Android refuses to
    // install an update signed with a different key than what's already
    // on the device, no matter how identical the app otherwise is.
    // Pointing at a real file inside the repo (keystore/debug.keystore,
    // committed once by the CI workflow the first time it runs after
    // this change — see build.yml) fixes this: every future build reuses
    // the exact same key, so updates install cleanly from here on.
    signingConfigs {
        getByName("debug") {
            storeFile = file("${rootProject.projectDir}/keystore/debug.keystore")
            storePassword = "junaidebug"
            keyAlias = "junaidebugkey"
            keyPassword = "junaidebug"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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
    // ProcessLifecycleOwner — whole-app (not per-Activity) foreground/
    // background signal, used by GenerationForegroundService to decide
    // whether the "Jun replying…" / "reply complete" notifications
    // should show at all.
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.room:room-runtime:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.google.mediapipe:tasks-text:0.10.21")
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    // llama.cpp/GGUF migration — switched from io.github.ljcamargo:llamacpp-kotlin
    // (0.4.0, immature, crashed natively on model load with zero JVM
    // stack trace or breadcrumb, undebuggable without adb/logcat) to
    // Llamatik: Maven Central, 135★, actively maintained, used in real
    // production apps (Llamatik Code IntelliJ plugin), and crucially
    // exposes onError callbacks instead of silently segfaulting.
    // Requires minSdk 26 (bumped above from 24).
    // Bumped 1.7.0 -> 1.9.1: v1.9.1 is the release maintainer ferranpons
    // confirmed (on issue #164) contains the fix for the UTF-8/emoji
    // SIGABRT crash — see GGUFChatEngine.kt, which now uses real
    // generateStream() again instead of the non-streaming workaround.
    implementation("com.llamatik:library:1.9.1")
}
