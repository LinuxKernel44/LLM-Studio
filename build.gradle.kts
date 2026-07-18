// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // No org.jetbrains.kotlin.android plugin: AGP 9+ compiles Kotlin sources automatically
    // ("built-in Kotlin"), applying that plugin explicitly is now an error, not just redundant.
    alias(libs.plugins.kotlin.compose) apply false
}
