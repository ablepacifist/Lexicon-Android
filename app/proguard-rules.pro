# Retrofit interfaces and kotlinx.serialization models are reflected over
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclassmembers class com.alexdyakin.lexicon.data.** { *; }
