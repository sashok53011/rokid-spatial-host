plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rokid.spatialhost.video"
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
    implementation(libs.coroutines.android)
    // api: PlaybackException appears in StreamPlayer's public callbacks
    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    // rtsp:// URLs resolve automatically when this module is on the classpath
    implementation(libs.media3.exoplayer.rtsp)
}
