plugins {
    id("com.android.application")
}

android {
    namespace = "com.aqrabmasjid.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aqrabmasjid.app"
        minSdk = 26
        // Google Play requires Android 16 (API 36) for new apps and updates
        targetSdk = 36
        // Each GitHub build gets a higher number (Google Play needs this to accept updates)
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1) + 100
        versionName = "1.1"
    }

    // Upload key for Google Play. The values come from GitHub secrets, never from the code.
    val ksFile = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        // Test APK (GitHub link): one fixed key, so a new version installs over the old one.
        // This key is only for the test APK, not for Google Play.
        getByName("debug") {
            storeFile = file("test-signing.keystore")
            storePassword = "aqrabtest"
            keyAlias = "aqrabtest"
            keyPassword = "aqrabtest"
        }
        if (ksFile != null && file(ksFile).exists()) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                // Our key uses the same password as the keystore, so this secret is optional
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
                    ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Android Auto (Car App Library)
    implementation("androidx.car.app:app:1.4.0")
    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Quran radio player (live streams, follows redirects, keeps playing in the background)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // Quran radio on the car screen (Android Auto)
    implementation("androidx.media3:media3-session:1.4.1")
    // Qibla camera (CameraX works on more phones than the old camera API, e.g. Honor)
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    // Prayer times (Umm Al-Qura and other methods)
    implementation("com.batoulapps.adhan:adhan:1.2.1")
}
