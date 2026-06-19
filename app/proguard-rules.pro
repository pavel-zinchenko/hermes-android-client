# Keep Gson DTOs (serialized via reflection).
-keep class com.hermes.android.data.dto.** { *; }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes Signature
-keepattributes *Annotation*
