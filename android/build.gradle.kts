buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP's built-in Kotlin uses this KGP runtime. Kadb 2.1.5 has Kotlin 2.4 metadata.
        // https://developer.android.com/build/releases/agp-9-0-0-release-notes
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
}
