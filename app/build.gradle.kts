plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.aaa"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.c11120855"
        minSdk = 29
        targetSdk = 35
        versionCode = 11120855
        versionName = "2026.11.12.0855"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            // CI uses the runner debug key; this release is not guaranteed to update an installed APK.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
