import java.util.Properties

plugins {
    id("com.android.application")
}

// Release key lives outside the project: %USERPROFILE%\.android-keys\headswap-release.properties
val releaseProps = Properties().apply {
    val f = file("${System.getProperty("user.home")}/.android-keys/headswap-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.headswap.desk"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.headswap.desk"
        minSdk = 33
        targetSdk = 37
        testInstrumentationRunner = "com.headswap.desk.qa.CompatibilityProbe"
        versionCode = 43
        versionName = "0.6.0"
        // Wireless CarLife uses the same desktop source and channel protocol as USB.
        buildConfigField("boolean", "WIRELESS_BETA", "true")
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    // Both APKs use the same pixels-to-dp calculation for their application windows.
    sourceSets.getByName("main").java.srcDir(rootProject.file("../tools/drivedeck-companion/shared"))

    packaging {
        // Preserve the notices shipped by each Bouncy Castle module used by Kadb.
        resources.merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Правила диагностики подключения общие с tools/logdiag.py: лежат в tools/, в APK копируются как assets/logdiag_rules.json
abstract class CopyLogdiagRules : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rules: RegularFileProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        rules.get().asFile.copyTo(File(dir, "logdiag_rules.json"), overwrite = true)
    }
}

val copyLogdiagRules = tasks.register<CopyLogdiagRules>("copyLogdiagRules") {
    rules.set(rootProject.layout.projectDirectory.file("../tools/logdiag_rules.json"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLogdiagRules, CopyLogdiagRules::outDir)
    }
}

dependencies {
    implementation(project(":desktop-panel-sdk"))
    implementation("androidx.core:core:1.7.0") // FileProvider for verified component APKs
    implementation("androidx.annotation:annotation:1.8.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // 2.1.5 keeps the transport alive when a not-yet-listening scrcpy socket rejects OPEN.
    implementation("com.flyfishxu:kadb:2.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    // В unit-тестах android.jar — заглушки (isReturnDefaultValues), настоящий org.json нужен LogDiag
    testImplementation("org.json:json:20240303")
}

// Сертификаты head unit AA (assets/aa/<профиль>/cert.pem, key.pem) не в git, в свежем worktree их нет.
// Подтягиваем из основного checkout (родитель git common dir) или из HEADSWAP_AA_CERTS / -Pheadswap.aaCertsDir.
// Release без них не собираем: APK без сертификатов падает у юзера с «Нет сертификата head unit».
val syncAaCerts = tasks.register("syncAaCerts") {
    val dst = file("src/main/assets/aa")
    val root = projectDir
    val override = System.getenv("HEADSWAP_AA_CERTS") ?: (project.findProperty("headswap.aaCertsDir") as String?)
    val release = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
    doLast {
        val need = listOf("hur", "jvc").flatMap { p -> listOf("$p/cert.pem", "$p/key.pem") }
        fun missing() = need.filter { !File(dst, it).isFile }
        if (missing().isNotEmpty()) {
            val src = override?.let(::File) ?: try {
                val p = ProcessBuilder("git", "rev-parse", "--path-format=absolute", "--git-common-dir")
                    .directory(root).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                if (p.waitFor() == 0) File(out).parentFile.resolve("android/app/src/main/assets/aa") else null
            } catch (e: Exception) {
                null
            }
            if (src != null && src.canonicalFile != dst.canonicalFile) {
                for (rel in missing()) {
                    val from = File(src, rel)
                    if (from.isFile) {
                        from.copyTo(File(dst, rel), overwrite = true)
                        logger.lifecycle("syncAaCerts: $rel <- $from")
                    }
                }
            }
        }
        val left = missing()
        if (left.isNotEmpty()) {
            // по-английски: консоль Windows в cp1251/cp866 превращает кириллицу в кашу
            val msg = "AA head unit certs missing in $dst: ${left.joinToString()}. " +
                "Put them there or point HEADSWAP_AA_CERTS / -Pheadswap.aaCertsDir to a directory with them"
            if (release) throw GradleException(msg) else logger.warn("syncAaCerts: $msg")
        }
    }
}

// This DeX fork never opens Android Auto. Its builds must not require or copy AA keys.
