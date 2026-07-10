# libVLC активно использует JNI — сохраняем все его классы.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.tabletplayer.** { *; }

-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
