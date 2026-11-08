plugins {
    id("com.android.application")
}

android {
    namespace = "dev.minilaunch"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.minilaunch"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "1.01"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideload build: sign with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = false
    }
}
