plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rokid.spatialhost.physics"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
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
    implementation(libs.kotlin.stdlib)
    // Jolt Physics bindings (single-precision). NOTE: the SpRelease AAR's
    // classes.jar is R8-stripped (empty) in published releases — SpDebug
    // ships the real JVM classes; its native lib is arm64-included.
    implementation("com.github.stephengold:jolt-jni-Android:7.0.0:SpDebug@aar")
}
