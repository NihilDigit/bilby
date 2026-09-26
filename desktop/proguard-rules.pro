# Bilby 桌面端 release 的 ProGuard 规则。只做裁剪:不优化、不混淆(理由见 build.gradle.kts)。
# Bilby 自己的代码不按名字查找类或成员(见 CLAUDE.md);下面保留的都是依赖里按名字反射、
# 经 JNI 回调或经 ServiceLoader 装载的东西,裁掉它们不会编译失败,只会在运行到那一步时崩。
-dontobfuscate

# Bilby 自己的代码整个保留,裁剪只作用于依赖。经 MethodHandles 按名字取出、交给 FFM 做 upcall
# 的方法(自绘标题栏的窗口过程)也由这一条保住,不必另写。
-keep class dev.bilby.** { *; }

# JNA 按接口方法名绑定 native 函数,结构体按字段名映射内存布局。Bilby 用 jna-platform 的
# User32 改窗口样式(无边框全屏、画中画),mediamp 也经它调 kernel32。
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-dontwarn com.sun.jna.**

# mediamp:mpv 的 JNI 回调按类名与方法签名从 native 侧查找,播放器工厂与画面表面经 ServiceLoader 装载
-keep class org.openani.mediamp.** { *; }
-dontwarn org.openani.mediamp.**

# zstd-jni 的原生代码按字段名读写 nativePtr 等字段,这些字段在 Java 侧没有读者
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# 内置 SQLite(Room 的驱动)经 JNI 调进 native,native 侧按类名与签名回找
-keep class androidx.sqlite.driver.bundled.** { *; }

# DataStore 的偏好文件是 protobuf-lite,按字段名反射读写消息
-keep class androidx.datastore.preferences.** { *; }

# ServiceLoader 装载的实现类。ProGuard 不像 R8 那样自动保留 META-INF/services 里列出的类
-keep class coil3.network.okhttp.internal.OkHttpNetworkFetcherServiceLoaderTarget { *; }
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider { *; }

# kotlinx.serialization:生成的 serializer 经伴生对象的 serializer() 查找
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }

# Ktor 与 OkHttp 引用了桌面 JVM 上不存在的可选依赖
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
-dontwarn okhttp3.internal.graal.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.jetbrains.annotations.**
