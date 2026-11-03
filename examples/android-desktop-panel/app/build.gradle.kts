plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.headswap.panel"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.headswap.panel"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "mode"
    productFlavors {
        create("example") {
            dimension = "mode"
            manifestPlaceholders["panelModeId"] = "example-panel"
        }
        create("starter") {
            dimension = "mode"
            applicationIdSuffix = ".starter"
            manifestPlaceholders["panelModeId"] = "open-dashboard-starter"
        }
        create("carbridgePreview") {
            dimension = "mode"
            applicationIdSuffix = ".carbridgepreview"
            versionCode = 2
            versionName = "1.1"
            manifestPlaceholders["panelModeId"] = "headswap-music-example"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val sdkAar = providers.gradleProperty("desktopPanelSdkAar").orNull
    if (sdkAar == null) implementation(project(":desktop-panel-sdk"))
    else implementation(files(sdkAar))
}
