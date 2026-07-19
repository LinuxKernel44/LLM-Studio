import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // No org.jetbrains.kotlin.android: AGP 9+ compiles Kotlin sources (src/main/kotlin) natively.
    alias(libs.plugins.kotlin.compose)
}

// Release signing is optional and local-only: keystore.properties (git-ignored, see
// keystore.properties.example) points at a keystore file that's never committed either. Without
// it, release builds simply stay unsigned - CI and other contributors can still build normally.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.linuxkernel44.llmstudio"
    compileSdk {
        // The backdrop (Liquid Glass) AAR requires compileSdk 37+ regardless of Compose's own
        // requirements; targetSdk/minSdk are unaffected and stay as configured below.
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.linuxkernel44.llmstudio"
        minSdk = 27
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
}

// Java (existing screens) and Kotlin (the Liquid Glass screen) sources coexist in this module; the
// Kotlin plugin compiles .kt files it finds under src/main/kotlin alongside src/main/java.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.drawerlayout)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.gson)

    // Kokoro local TTS: model package is a .tar.bz2, downloaded at runtime (too large to bundle).
    implementation(libs.commons.compress)

    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)

    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.runtime.compose)

    // Liquid Glass theme: Compose is used only for this one screen shell (see GlassMainActivity.kt);
    // every other screen stays on the existing View/ViewBinding stack.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.runtime.livedata)
    implementation(libs.activity.compose)
    implementation(libs.backdrop)

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
