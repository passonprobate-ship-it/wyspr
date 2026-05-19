# Keystone hardening ProGuard / R8 rules
#
# Strip all Log.d / Log.v / Log.i calls in release builds.
# Log.w and Log.e are kept — those are guarded against payload leakage
# in code already, and we want them in the device log if something
# goes catastrophically wrong.

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Kotlin metadata — keep, Hilt and Compose need it.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }

# Noise protocol + libsodium native bindings — never strip. JNA in
# particular uses reflection to bind native methods, and any
# class-name renaming breaks the loader at runtime.
-keep class com.southernstorm.noise.** { *; }
-keepclassmembers class com.southernstorm.noise.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-keepclassmembers class com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keepclasseswithmembers class * { @com.sun.jna.* <methods>; }

# Room — generated DAO impls reflect on the entity constructors.
-keep class com.keystone.core.database.entities.** { *; }
-keepclassmembers class com.keystone.core.database.entities.** { <init>(...); }

# Hilt-generated entry points have predictable names; keep them.
-keep,allowobfuscation class * extends androidx.lifecycle.ViewModel
-keep class * implements dagger.internal.GeneratedComponent { *; }

# Defensive: strip println too. Anything calling println in release
# would be a payload-leakage bug; we'd rather it be silent.
-assumenosideeffects class kotlin.io.ConsoleKt {
    public static void println(java.lang.Object);
    public static void println();
}
-assumenosideeffects class java.io.PrintStream {
    public void println(java.lang.Object);
}

# ZXing's reader stack — reflection on hint types.
-keep class com.google.zxing.** { *; }

# JNA ships AWT shims for desktop JVMs that never load on Android.
# R8 can't see java.awt and refuses to compile without these rules.
-dontwarn java.awt.**
-dontwarn javax.swing.**
