plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.knightdx.glassestunes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.knightdx.glassestunes"
        minSdk = 29 // Android 10, like the Meta AI app
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // 64-bit ARM only: every current Samsung phone, and it keeps the APK well under GitHub's 100 MB limit.
            abiFilters += listOf("arm64-v8a")
            // ./gradlew -PwithEmulator assembleDebug also runs on the x86_64 emulator.
            if (project.hasProperty("withEmulator")) abiFilters += "x86_64"
        }
    }

    signingConfigs {
        // The same key as every earlier build (so updates install in place), shared with Glasses Camera
        // so "Jarvis, take a photo" can use its signature-protected photo request.
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
    // Offline "Jarvis" wake word (open source, Apache 2.0).
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    testImplementation("junit:junit:4.13.2")

}
