# libVLC uses native/JNI entry points and internal reflection.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.libvlc.util.** { *; }
-keep class org.videolan.libvlc.interfaces.** { *; }
-dontwarn org.videolan.libvlc.**

# Android components are referenced from AndroidManifest and kept by the Android Gradle Plugin.
# Не держим весь com.tabletplayer целиком, чтобы R8 мог оптимизировать обычный Java-код приложения.
