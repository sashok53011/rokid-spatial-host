plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rokid.spatialhost"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rokid.spatialhost"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Filament ships 4 ABIs; the OPi/phones are arm64 — halves the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Internal distribution: sign with the debug keystore so
            // assembleRelease installs over existing debug builds.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-bridge"))
    implementation(project(":core-render"))
    implementation(project(":core-scene"))
    implementation(project(":core-input"))
    implementation(project(":core-video"))
    implementation(project(":core-battery"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.android)
}
