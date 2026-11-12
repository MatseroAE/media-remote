plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.diy.mediaremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.diy.mediaremote"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    // One fixed key for every build (Android Studio and GitHub Actions), so a newer APK installs
    // over an older one without uninstalling. It's a hobby key and deliberately public: anyone
    // can build and sign this app.
    signingConfigs {
        create("shared") {
            storeFile = file("signing.keystore")
            storePassword = "mediaremote"
            keyAlias = "mediaremote"
            keyPassword = "mediaremote"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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

// No extra libraries: the app uses only the Android framework.
