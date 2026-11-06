plugins {
    id("com.android.application")
}

android {
    namespace = "com.wonderhoylabs.otori"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wonderhoylabs.otori"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // Set these to sign with your own key. Without them a release APK still installs, because it
    // falls back to the debug key, but Android refuses to update an app across different keys.
    signingConfigs {
        System.getenv("OTORI_KEYSTORE")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("OTORI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OTORI_KEY_ALIAS")
                keyPassword = System.getenv("OTORI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "ttf"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
