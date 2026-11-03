pluginManagement {
    plugins {
        id("com.android.application") version "9.4.1"
        id("com.android.library") version "9.4.1"
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.library") useModule("com.android.tools.build:gradle:9.4.1")
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "headswap-desktop-panel"
include(":app")

if (!providers.gradleProperty("desktopPanelSdkAar").isPresent) {
    include(":desktop-panel-sdk")
    project(":desktop-panel-sdk").projectDir = file("../../sdk/desktop-panel-v1")
}
