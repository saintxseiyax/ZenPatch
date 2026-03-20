# Patcher module ProGuard rules
-keep class dev.zenpatch.patcher.** { *; }
-keepattributes *Annotation*
-keepattributes Signature
-dontwarn com.android.apksig.**
-dontwarn sun.security.x509.**
