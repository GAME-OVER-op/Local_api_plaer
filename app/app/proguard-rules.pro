# Правила R8 для релизной сборки (minifyEnabled true).

# libVLC активно использует JNI: сохраняем все его классы и члены,
# иначе нативный код не найдёт нужные символы и плеер упадёт.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# Любые классы с native-методами не переименовываем и не удаляем.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Наши классы (Activity держатся из манифеста, но подстрахуемся).
-keep class com.tabletplayer.** { *; }

# Сохраняем стандартные атрибуты для отладки стектрейсов.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
