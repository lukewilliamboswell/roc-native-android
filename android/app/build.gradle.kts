plugins {
    id("com.android.application")
}

android {
    namespace = "dev.roc.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.roc.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        // The Roc libraries are built for arm64 only (../build.sh).
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The desktop smoke test (../desktop) lives outside this source set.
}
