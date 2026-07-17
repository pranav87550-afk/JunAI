plugins {
    // BUGFIX: bumped from 8.5.2 — llamacpp-kotlin:0.4.0 pulls in
    // androidx.core-ktx:1.18.0 transitively, which requires compileSdk 36
    // and AGP 8.9.1+ to compile against (confirmed via CI build failure:
    // checkDebugAarMetadata rejected compileSdk 35 against this AAR).
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
}
