plugins {
    id("com.android.application") version "8.5.2" apply false
    // BUGFIX: litertlm-android (added for ChatEngine.kt/Qwen3) ships
    // Kotlin 2.2.0 metadata — the 1.9.22 compiler can't read metadata
    // newer than itself, hence the "incompatible version of Kotlin"
    // build failure. Bumped to 2.2.0 so it can. KSP version follows
    // Kotlin's own versioning scheme (kotlinVersion-kspPatch) — if this
    // exact KSP patch fails to resolve, check
    // github.com/google/ksp/releases for the latest patch published
    // against Kotlin 2.2.0 and swap the suffix.
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
}
