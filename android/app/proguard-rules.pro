# AgriConnect — R8 / ProGuard rules (release).
#
# The release build is minified (`isMinifyEnabled = true`) and resource-shrunk.
# These rules are the minimum needed to keep reflection-based libraries working.
# They are intentionally conservative: an over-broad keep rule costs a little APK
# size; a missing one costs a runtime crash that only appears in release.

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
# The generated `$$serializer` classes and `Companion.serializer()` are reached by
# reflection. Kotlin serialization ships consumer rules in recent versions, but we
# keep the serializers of our wire DTOs explicitly to be safe across versions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class bw.agriconnect.data.remote.dto.** {
    *** Companion;
}
-keepclasseswithmembers class bw.agriconnect.data.remote.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class bw.agriconnect.data.remote.dto.**$$serializer { *; }

# ---------------------------------------------------------------------------
# Retrofit
# ---------------------------------------------------------------------------
# Retrofit reads generic signatures and interface annotations at runtime.
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Retrofit 2.11 ships rules, but the service interface + its suspend return types
# must be preserved so `create()` can resolve them.
-keep interface bw.agriconnect.data.remote.ApiService { *; }

# ---------------------------------------------------------------------------
# OkHttp
# ---------------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# ---------------------------------------------------------------------------
# Room
# ---------------------------------------------------------------------------
# Room generates implementations that are instantiated by name; keep the database
# and DAO contracts.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Hilt / Dagger
# ---------------------------------------------------------------------------
# Hilt-generated components are referenced from the generated Application.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ---------------------------------------------------------------------------
# WorkManager / Hilt worker
# ---------------------------------------------------------------------------
# WorkManager instantiates workers reflectively by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class bw.agriconnect.data.sync.SyncWorker { <init>(...); }

# ---------------------------------------------------------------------------
# osmdroid
# ---------------------------------------------------------------------------
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ---------------------------------------------------------------------------
# Vico charts
# ---------------------------------------------------------------------------
-keep class com.patrykandpatrick.vico.** { *; }
-dontwarn com.patrykandpatrick.vico.**

# ---------------------------------------------------------------------------
# Domain/wire models
# ---------------------------------------------------------------------------
# Domain models are value types used in sealed results; keep them readable in
# crash reports even though R8 may not need them.
-keepnames class bw.agriconnect.domain.model.** { *; }

# Keep enum `values()`/`valueOf()` — kotlinx.serialization stores enums by name.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Coroutines: silence the optional-DebugProbes warning.
-dontwarn kotlinx.coroutines.**
