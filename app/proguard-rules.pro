# ZenPatch Manager ProGuard rules

# Keep Xposed API classes
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }

# Keep Compose (handled by Compose compiler plugin, but safety net)
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# Keep Timber
-dontwarn org.jetbrains.annotations.**
-keep class timber.log.Timber { *; }

# Keep navigation args
-keepclassmembers class * extends androidx.navigation.NavArgs { *; }

# Keep ViewModel constructors
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel { <init>(android.app.Application); }

# Keep data classes used in serialization
-keep class dev.zenpatch.manager.data.** { *; }

# Keep ConfigProvider
-keep class dev.zenpatch.manager.provider.ConfigProvider { *; }

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Shizuku
-keep class rikka.shizuku.** { *; }

# Keep reflection targets
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

# R8 full mode compatibility
-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn javax.annotation.**
