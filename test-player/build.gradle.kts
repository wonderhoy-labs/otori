plugins {
    id("com.android.application")
}

// A stand-in third party music app, used only to test the island against a real MediaSession on the emulator.
android {
    namespace = "com.wonderhoylabs.otori.testplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wonderhoylabs.otori.testplayer"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "m4a"
    }
}
