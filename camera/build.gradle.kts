plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Glasses Camera: take photos with Ray-Ban Meta glasses from a Galaxy Watch button (or Glasses Tunes'
// "Jarvis, take a photo"), via Meta's Wearables Device Access Toolkit.
android {
    namespace = "com.knightdx.glassescamera"
    compileSdk = 35

    defaultConfig {
        // The watch app (wear module) uses the same id: the Wear data layer only connects matching apps.
        applicationId = "com.knightdx.glassescamera"
        minSdk = 29 // required by the toolkit
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Developer Mode doesn't use attestation, so these stay empty (as in Meta's sample).
        manifestPlaceholders["mwdat_application_id"] = ""
        manifestPlaceholders["mwdat_client_token"] = ""

        ndk {
            abiFilters += listOf("arm64-v8a")
            // ./gradlew -PwithEmulator also runs on the x86_64 emulator.
            if (project.hasProperty("withEmulator")) abiFilters += "x86_64"
        }
    }

    signingConfigs {
        // Shared by Glasses Tunes, Glasses Camera and the watch app.
        getByName("debug") {
            storeFile = rootProject.file("signing/glassestunes.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // Debug build plus Meta's simulated glasses, for the on-device camera test only.
        create("mock") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
        }
    }
    testBuildType = "mock"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.meta.wearable:mwdat-core:1.0.0")
    implementation("com.meta.wearable:mwdat-camera:1.0.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    // Google Play services pulls in an old Fragment, which breaks Activity Result callbacks.
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    add("mockImplementation", "com.meta.wearable:mwdat-mockdevice:1.0.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
