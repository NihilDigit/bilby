# Skiko 与 Skia 已由 Compose 默认规则整包保留,mediamp 对 Skiko 内部字段的反射不必另写。

# mediamp 的原生层经 JNI 按类名、方法名回调 Kotlin,工厂又经 ServiceLoader 按类名加载。
# 按回调清单逐个 keep 会在它升级时静默失效,漏一项就是运行时崩溃;库本身不大,整包保留。
-keep class org.openani.mediamp.** { *; }

# mediamp 的原生库加载器依赖 JNA,JNA 按名字反射访问自己的结构体与回调
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
