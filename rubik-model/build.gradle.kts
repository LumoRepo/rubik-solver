// rubik-model: Pure Kotlin library — zero Android dependencies
// Shared across all platforms (JVM, Android, iOS) via Kotlin Multiplatform.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {}
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
