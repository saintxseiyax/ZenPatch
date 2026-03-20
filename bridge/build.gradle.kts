plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.zenpatch.bridge"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("proguard-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-31"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
        prefab = true
    }

    packaging {
        jniLibs {
            // Ensure .so files are not compressed (required for mmap)
            keepDebugSymbols += listOf("**/*.so")
        }
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    // LSPlant via Maven prefab for native linking
    implementation(libs.lsplant)
}
