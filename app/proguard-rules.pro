# kotlinx.serialization: правила поставляются библиотекой; модели конфигурации держим явно.
-keep class io.personal.filemanager.data.settings.** { *; }

# Архиваторы: необязательные зависимости Commons Compress отсутствуют намеренно.
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**
# JNI-классы zstd вызываются из нативного кода.
-keep class com.github.luben.zstd.** { *; }
