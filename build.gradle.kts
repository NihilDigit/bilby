// AGP 9 起 Kotlin 支持内置,不再应用 org.jetbrains.kotlin.android(应用了会直接报错)。
// 代价是 KGP 版本由 AGP 的运行时依赖决定(9.3.1 带的是 2.2.10),要用更新的 Kotlin
// 只能在这里覆盖 buildscript classpath —— 版本目录的 libs 访问器在 buildscript 块里不可用,
// 所以这两行必须写字面量,改版本时记得和 gradle/libs.versions.toml 一起改。
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.11")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
