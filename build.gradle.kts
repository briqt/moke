// moke（墨客）· 根构建脚本
// 具体插件版本见 gradle/libs.versions.toml
buildscript {
    dependencies {
        // AGP 9 内置 Kotlin 支持，自带的 KGP 版本较旧；在此锁定到 catalog 的 Kotlin 版本（与 Compose 编译器插件一致）。
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
