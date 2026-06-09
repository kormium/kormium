import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    // Applied to the publishable subprojects below (not to the root itself).
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap")
        }
    }
    dependencies {
        // Kotlin Gradle plugin for all modules (they apply kotlin("multiplatform") without a version).
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
        // Android Gradle plugin for the modules that declare an androidTarget() (Compose
        // Multiplatform support). They apply id("com.android.library") without a version.
        classpath("com.android.tools.build:gradle:9.2.1")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap")
        }
    }
}

// iOS simulator tests need an installed iOS simulator runtime (Xcode). On a machine without
// one the task fails with "Xcode does not support simulator tests for ios_simulator_arm64",
// breaking `check` — unlike the other unavailable native targets, which Kotlin auto-disables.
// Gate the simulator test tasks on runtime availability so `check` stays runnable. Note that
// `xcrun --show-sdk-path` is not a reliable signal (it succeeds even with no runtime
// installed); the presence of an iOS entry in `simctl list runtimes` is. Override with
// -PenableIosSimulatorTests=true|false.
val iosSimulatorTestsEnabled: Boolean by lazy {
    when (providers.gradleProperty("enableIosSimulatorTests").orNull) {
        "true" -> true
        "false" -> false
        else -> runCatching {
            val process = ProcessBuilder("xcrun", "simctl", "list", "runtimes")
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.lineSequence().any { it.contains("iOS") }
        }.getOrDefault(false)
    }
}

allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        onlyIf("no iOS simulator runtime available") { iosSimulatorTestsEnabled }
    }
}

// Publishing configuration shared by every published library module + the BOM. Credentials
// (mavenCentralUsername/Password) and the GPG key (signingInMemoryKey/Password) are supplied
// out-of-band — see gradle.properties for the property names.
val publishableModules = setOf(
    "korm-core",
    "korm-postgres",
    "korm-jdbc",
    "korm-sqlite",
    "korm-r2dbc",
    "korm-ktor",
    "korm-ktor-di",
    "korm-ktor-koin",
    "korm-bom",
)

subprojects {
    if (name !in publishableModules) return@subprojects

    apply(plugin = "com.vanniktech.maven.publish")

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        signAllPublications()
        coordinates(group.toString(), name, version.toString())

        pom {
            name.set("korm")
            description.set("Korm — a simple Kotlin Multiplatform ORM (Postgres + SQLite, JVM + Native).")
            inceptionYear.set("2024")
            url.set("https://github.com/knyazevs/korm")
            licenses {
                license {
                    name.set("MIT")
                    url.set("https://opensource.org/licenses/MIT")
                    distribution.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("knyazevs")
                    name.set("Sergey Knyazev")
                    email.set("sknyazev@vk.com")
                    url.set("https://github.com/knyazevs")
                }
            }
            scm {
                url.set("https://github.com/knyazevs/korm")
                connection.set("scm:git:https://github.com/knyazevs/korm.git")
                developerConnection.set("scm:git:ssh://git@github.com/knyazevs/korm.git")
            }
        }
    }
}
