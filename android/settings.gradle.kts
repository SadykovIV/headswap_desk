pluginManagement {
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "com.android.library") useModule("com.android.tools.build:gradle:${requested.version ?: "9.4.1"}")
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
        // Kadb's pairing implementation uses the pinned SPAKE2 Java library.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.Flyfish233") }
        }
    }
}

rootProject.name = "headswap"
include(":app", ":desktop-panel-sdk")
project(":desktop-panel-sdk").projectDir = file("../sdk/desktop-panel-v1")
