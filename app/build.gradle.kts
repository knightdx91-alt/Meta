plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.knightdx.glassestunes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.knightdx.glassestunes"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Meta Wearables Device Access Toolkit: Developer Mode doesn't use attestation, so these stay
        // empty (as in Meta's sample). A bare 0 would be stored as a number, which the toolkit can't read.
        manifestPlaceholders["mwdat_application_id"] = ""
        manifestPlaceholders["mwdat_client_token"] = ""

        ndk {
            // 64-bit ARM only: every current Samsung phone, and it keeps the APK well under GitHub's 100 MB limit.
            abiFilters += listOf("arm64-v8a")
            // ./gradlew -PwithEmulator assembleDebug also runs on the x86_64 emulator.
            if (project.hasProperty("withEmulator")) abiFilters += "x86_64"
        }
    }

    signingConfigs {
        // One key for the phone and watch apps: the Wear data layer only connects apps signed alike,
        // and keeping it means updates install over the existing app.
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
    // Glasses camera (Meta Wearables Device Access Toolkit).
    implementation("com.meta.wearable:mwdat-core:1.0.0")
    implementation("com.meta.wearable:mwdat-camera:1.0.0")
    // Messages from the watch app.
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    // Google Play services pulls in an old Fragment, which breaks Activity Result callbacks.
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Offline "Jarvis" wake word (open source, Apache 2.0).
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation("junit:junit:4.13.2")
    // On-device test of the glasses camera against Meta's simulated glasses.
    add("mockImplementation", "com.meta.wearable:mwdat-mockdevice:1.0.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
