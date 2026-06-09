# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepclassmembernames,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Moshi — KSP generates adapters; no reflection needed for annotated classes.
# Keep the auth DTO (not code-generated, uses reflection adapter as fallback).
-keepclassmembers class com.victorypoint.zldreventreporter.data.auth.TokenResponse { *; }

# Room — keep entity field names for SQLite column mapping
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.**

# JSoup
-dontwarn org.jsoup.**
