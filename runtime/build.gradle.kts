plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.zenpatch.runtime"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("proguard-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
    }
    // NOTE: The :runtime module does NOT build native code.
    // Native code (LSPlant + zenpatch_bridge) is compiled exclusively by the :bridge module.
    // The :runtime module loads those .so files at runtime via System.loadLibrary().
}

dependencies {
    implementation(project(":xposed-api"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
}
