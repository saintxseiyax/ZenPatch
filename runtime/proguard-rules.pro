# Runtime module ProGuard rules - keep everything since it's injected

# Keep all runtime classes (they're loaded dynamically)
-keep class dev.zenpatch.runtime.** { *; }
-keepclassmembers class dev.zenpatch.runtime.** { *; }

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep ZenPatchAppProxy as Application class
-keep class dev.zenpatch.runtime.ZenPatchAppProxy { *; }
-keep class dev.zenpatch.runtime.HookDispatcher { *; }
-keep class dev.zenpatch.runtime.NativeBridgeXposedImpl { *; }

# Keep NativeBridge JNI
-keep class dev.zenpatch.runtime.NativeBridge {
    *;
    native <methods>;
}

# Keep HiddenApiBypass JNI
-keep class dev.zenpatch.runtime.HiddenApiBypass {
    native <methods>;
}

# Keep reflection targets
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-keepattributes Exceptions
