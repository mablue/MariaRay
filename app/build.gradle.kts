plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNum = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)

android {
    namespace = "com.mariaray.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mariaray.app"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNum
        versionName = "1.$buildNum"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("stable") {
            storeFile = file("mariaray.jks")
            storePassword = "MariaRay2024!"
            keyAlias = "mariaray"
            keyPassword = "MariaRay2024!"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libxray.so"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
