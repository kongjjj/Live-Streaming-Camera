// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // 注意：AGP 9.0 已內建 Kotlin 支援，無需再引入 kotlin-android 插件
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}