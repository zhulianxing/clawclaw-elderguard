-keep class com.google.mediapipe.** { *; }
-keep class com.elderguard.care.** { *; }

# ============ AnNest 安全混淆规则 ============

# MediaPipe
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Vosk 语音
-keep class com.alphacephei.** { *; }
-dontwarn com.alphacephei.**
-keep class org.vosk.** { *; }

# BouncyCastle 加密
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin 协程
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# 保留 License 校验逻辑（防止被优化掉但也不暴露 SALT）
-keep class com.elderguard.care.data.LicenseManager { *; }
-keep class com.elderguard.care.data.Web3LicenseManager { *; }

# JNA（Vosk 依赖）
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# AutoValue / JavaPoet 缺失类（编译时注解处理器，运行时无需）
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
-dontwarn autovalue.shaded.com.squareup.javapoet.**
