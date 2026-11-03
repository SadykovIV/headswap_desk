pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins { id("com.android.library") version "9.4.1" }
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "com.android.library") useModule("com.android.tools.build:gradle:9.4.1")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "headswap-profile-sdk"
