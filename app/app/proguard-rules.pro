# libVLC использует JNI и внутреннюю рефлексию; оставляем его публичную Java-обвязку.
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**

# Компоненты из AndroidManifest и так считаются точками входа R8. Явно сохраняем
# только их конструкторы, разрешая оптимизацию и обфускацию всего остального кода.
-keep,allowoptimization,allowobfuscation class com.tabletplayer.App { public <init>(); }
-keep,allowoptimization,allowobfuscation class com.tabletplayer.*Activity { public <init>(); }
-keep,allowoptimization,allowobfuscation class com.tabletplayer.DownloadService { public <init>(); }

# Полезно для корректных callback/дженерик-сигнатур библиотек; код приложения
# больше не защищён широким -keep и полноценно оптимизируется R8.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
