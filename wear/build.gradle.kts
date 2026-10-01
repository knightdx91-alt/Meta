plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Galaxy Watch companion: one job, ask the phone to take a photo with the glasses.
android {
    namespace = "com.knightdx.glassescamera.watch"
    compileSdk = 35

    defaultConfig {
        // Must match the phone app: the Wear data layer only connects apps with the same id and signing key.
        applicationId = "com.knightdx.glassescamera"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/glassestunes.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
}
