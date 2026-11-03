plugins {
    id("com.android.application") version "9.4.1"
}

android {
    namespace = "com.example.headswap.displayinfo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.headswap.displayinfo"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
