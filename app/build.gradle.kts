plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.secondscreen.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.secondscreen.app"
        minSdk = 26
        targetSdk = 34
        // Build number from GitHub Actions, so each APK is newer than the previous one
        val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = runNumber
        versionName = "1.$runNumber"
    }

    // Fixed key so every CI build can be installed over the previous one
    signingConfigs {
        getByName("debug") {
            storeFile = file("secondscreen-debug.jks")
            storePassword = "android"
            keyAlias = "secondscreen"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
