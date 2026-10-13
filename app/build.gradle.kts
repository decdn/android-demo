plugins {
    id("com.android.application")
}

android {
    namespace = "dev.decdn.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.decdn.demo"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Must match the rustls-platform-verifier-android version that
    // rustls-platform-verifier 0.7.1 requires in rust/Cargo.lock. The lockfile
    // also holds 0.1.1 for an older verifier elsewhere in the graph, so the
    // version is pinned here instead of parsed from it.
    implementation("org.rustls:rustls-platform-verifier:0.2.0")
}
