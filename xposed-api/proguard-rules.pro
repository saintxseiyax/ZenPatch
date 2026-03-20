# Xposed API ProGuard rules - keep full API surface
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }
-keepclassmembers class de.robv.android.xposed.** { *; }
-keepattributes *Annotation*
-keepattributes Signature
