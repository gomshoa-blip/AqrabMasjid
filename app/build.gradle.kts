plugins {
    id("com.android.application")
}

android {
    namespace = "com.aqrabmasjid.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aqrabmasjid.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // Prayer times (Umm Al-Qura and other methods)
    implementation("com.batoulapps.adhan:adhan:1.2.1")
}
