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
