plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStoreFile = System.getenv("SIGNING_STORE_FILE")
val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "app.aaa"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.c11100423"
        minSdk = 29
        targetSdk = 35
        versionCode = 11100423
        versionName = "2026.11.10.0423"
        ndk { abiFilters += "arm64-v8a" }
    }
    if (hasReleaseSigning) {
        signingConfigs.create("stableRelease") {
            storeFile = file(signingStoreFile!!)
            storePassword = signingStorePassword
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
        }
    }
    buildTypes {
        release {
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("stableRelease")
                else signingConfigs.getByName("debug")
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
