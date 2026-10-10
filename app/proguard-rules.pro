# StormStream proguard rules
-keepattributes *Annotation*, Signature, InnerClasses

# Kotlin serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep data models (used by JSON de/serializers for extensions, repos, plugins)
-keep class com.stormstream.app.data.** { *; }
-keep class com.stormstream.app.providers.** { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# JSoup / Ksoup
-keep class org.jsoup.** { *; }
-keep class com.fleeksoft.ksoup.** { *; }

# libmpv wrapper (JNI)
-keep class is.xyz.mpv.** { *; }

# QuickJS wrapper (JNI + native bindings)
-keep class com.dokar.quickjs.** { *; }
