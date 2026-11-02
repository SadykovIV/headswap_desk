pluginManagement {
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
include(":app")
