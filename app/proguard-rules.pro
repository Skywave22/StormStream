# StormStream proguard rules
-keepattributes *Annotation*, Signature, InnerClasses

# Kotlin serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep data models for plugin IPC reflection (used by JSON de/serializers)
-keep class com.stormstream.app.data.** { *; }
-keep class com.stormstream.app.providers.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# JSoup
-keep class org.jsoup.** { *; }
