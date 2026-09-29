plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.knightdx.glassestunes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.knightdx.glassestunes"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Samsung phones are ARM; skipping emulator ABIs keeps the APK small.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // ./gradlew -PwithEmulator assembleDebug also runs on the x86_64 emulator.
            if (project.hasProperty("withEmulator")) abiFilters += "x86_64"
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
