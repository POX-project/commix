# R8 Full Mode configuration for Commix Pro Camera
# Strip unused JVM bytecode while strictly preserving JNI native entry points

# Preserve all JNI native methods and their declaring classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve Commix JNI Bridges
-keep class com.txor.commix.bridge.** { *; }
-keepclassmembers class com.txor.commix.bridge.** {
    <methods>;
    <fields>;
}

# Preserve Native Telemetry and Models
-keep class com.txor.commix.model.** { *; }
-keepclassmembers class com.txor.commix.model.** {
    <methods>;
    <fields>;
}

# AndroidX ExifInterface
-keep class androidx.exifinterface.** { *; }
-dontwarn androidx.exifinterface.**

# AndroidX HeifWriter
-keep class androidx.heifwriter.** { *; }
-dontwarn androidx.heifwriter.**

# Jetpack Compose Rules
-keep class androidx.compose.ui.platform.** { *; }

# Dalvik FastNative / CriticalNative annotations
-keepattributes *Annotation*
-keep class dalvik.annotation.optimization.** { *; }
