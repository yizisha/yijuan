# FlowReader ProGuard Rules
# Optimized for release builds with R8

# Shared interface for the bundled optional speech engine and controls.
-keep class com.flowreader.plugin.api.** { *; }

# ========================================
# Basic Android & Kotlin Rules
# ========================================
-keepattributes Signature, InnerClasses, *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes EnclosingMethod, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations

# Keep line numbers for crash reports
-renamesourcefileattribute SourceFile

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ========================================
# Room Database
# ========================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Database class *
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-dontwarn androidx.room.paging.**
-dontwarn androidx.room.**

# ========================================
# Hilt Dependency Injection
# ========================================
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.* <fields>;
}
-keepclasseswithmembers class * {
    @dagger.hilt.* <methods>;
}
-keep class * extends dagger.hilt.* { *; }
-dontwarn dagger.hilt.**
-dontwarn hilt_aggregated_deps_*

# ========================================
# Jetpack Compose
# ========================================
# Compose libraries ship their own consumer rules. Keeping the entire package also retained
# every unused extended icon and prevented R8 from optimizing the UI code.
-dontwarn androidx.compose.**

# ========================================
# ViewModel & Lifecycle
# ========================================
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ========================================
# Data Classes & Models
# ========================================
-keep class com.flowreader.app.domain.model.** { *; }
-keep class com.flowreader.app.data.local.entity.** { *; }
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ========================================
# Readium EPUB Toolkit
# ========================================
-keep class org.readium.** { *; }
-dontwarn org.readium.**

# ========================================
# JSoup HTML Parser
# ========================================
-keep class org.jsoup.** { *; }
-keeppackagenames org.jsoup.nodes
-keepclassmembers class org.jsoup.** { *; }

# ========================================
# Coil Image Loading
# ========================================
-keep class coil.** { *; }
-dontwarn coil.**

# ========================================
# Native Methods & Enums
# ========================================
-keepclasseswithmembernames class * {
    native <methods>;
}

# The bundled speech JNI resolves config/data classes and their fields by their original names.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.flowreader.app.util.SpeechPcmCallback { *; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ========================================
# Optimization Settings
# ========================================
-optimizationpasses 5
-allowaccessmodification
-dontusemixedcaseclassnames

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ========================================
# Warnings Suppression (Known Safe)
# ========================================
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
