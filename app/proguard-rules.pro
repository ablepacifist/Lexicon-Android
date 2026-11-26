# Retrofit interfaces and kotlinx.serialization models are reflected over
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclassmembers class com.alexdyakin.lexicon.data.** { *; }
# Keep the classes themselves, not only their members. A reply class that no code reads (the
# app ignores RegisterResponse) appears only in the API method's generic signature; R8 deleted
# it, Retrofit saw Object, and every sign-up failed in release with "Unable to create converter".
-keep @kotlinx.serialization.Serializable class com.alexdyakin.lexicon.** { *; }

# ── Lexi's on-device speech ─────────────────────────────────────────────────────────
# Vosk reaches its native library through JNA, which finds Java classes and callbacks by
# name at run time; renamed or removed, wake-word listening dies in release only.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
-keep class org.vosk.** { *; }

# ONNX Runtime's JNI code looks up its Java classes and fields by name.
-keep class ai.onnxruntime.** { *; }

# ── Annotation-only references with no runtime class ───────────────────────────────
# Tink (androidx.security.crypto) and others mention these; they are never loaded.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.osmdroid.**

# ── Media3 session commands are matched by name across the service boundary ─────────
-keep class com.alexdyakin.lexicon.media.** { *; }

# Readable stack traces from testers' crash reports.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
