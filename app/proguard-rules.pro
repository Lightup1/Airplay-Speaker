# Keep JNI callback interface (called from native code)
-keep class io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class * implements io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }

# Keep NativeBridge native methods
-keep class io.github.jqssun.airplay.bridge.NativeBridge { *; }

# LiteRT uses JNI entry points and reflective Kotlin metadata in release builds.
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
