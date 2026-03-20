plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.zenpatch.privilege"
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
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.timber)

    // Shizuku API
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Dhizuku API
    // implementation(libs.dhizuku.api)  // Uncomment when available in Maven
}
