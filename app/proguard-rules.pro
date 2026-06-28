# JunAI ProGuard Rules

# ── Room ──
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}

# ── Kotlin ──
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ── OkHttp ──
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ── JunAI entities & DAOs (Room needs these at runtime) ──
-keep class com.junai.app.** extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class com.junai.app.** { *; }
-keep @androidx.room.Entity class com.junai.app.memory.** { *; }
-keep @androidx.room.Entity class com.junai.app.reasoning.** { *; }
-keep @androidx.room.Entity class com.junai.app.planning.** { *; }
-keep @androidx.room.Dao interface com.junai.app.** { *; }
-keep @androidx.room.Dao interface com.junai.app.memory.** { *; }
-keep @androidx.room.Dao interface com.junai.app.reasoning.** { *; }
-keep @androidx.room.Dao interface com.junai.app.planning.** { *; }

# ── JSON (jun_knowledge.json parsing) ──
-keepclassmembers class * {
    @org.json.* <fields>;
}

# ── Android components ──
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
