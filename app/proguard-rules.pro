# ProGuard rules for Pocket Financer

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Room entities
-keep class com.pocketfinancer.data.db.entity.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep llama.cpp JNI
-keep class com.pocketfinancer.inference.** { *; }

# Tink references these compile-time-only Error Prone annotations. They are
# intentionally absent from the packaged runtime and safe for R8 to ignore.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
