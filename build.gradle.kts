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
        // Compose Multiplatform for the wasmJs todo sample. The Compose gradle plugin (libs/DSL)
        // plus the Kotlin Compose compiler plugin (decoupled from the gradle plugin since Kotlin
        // 2.0, versioned with Kotlin). The sample applies both without a version.
        classpath("org.jetbrains.compose:compose-gradle-plugin:1.11.1")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.0")
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

// Kotlin's wasm/js yarn install runs with --ignore-scripts, which stops native npm packages
// (e.g. better-sqlite3 in kormium-sqlite-node) from building/fetching their native binary. Allow
// install scripts so those modules work under the Node test runner.
//
// SUPPLY-CHAIN NOTE: this re-enables arbitrary postinstall scripts for every js/wasm dependency, so
// a compromised (transitive) package could run code at install time. The exposure is bounded: npm
// versions are pinned and the resolved tree is committed in kotlin-js-store/*yarn.lock, so installs
// are reproducible and auditable. Review lockfile changes when bumping a Wasm/Node engine's deps.
plugins.withType(org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin::class.java) {
    the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec>().ignoreScripts.set(false)
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
    "kormium-core",
    "kormium-postgres",
    "kormium-postgres-dialect",
    "kormium-mysql",
    "kormium-mysql-dialect",
    "kormium-jdbc",
    "kormium-sqlite",
    "kormium-sqlite-dialect",
    "kormium-wasm-driver",
    "kormium-sqlite-wasm",
    "kormium-sqlite-node",
    "kormium-postgres-node",
    "kormium-mysql-node",
    "kormium-r2dbc",
    "kormium-observe",
    "kormium-migrate",
    "kormium-ktor",
    "kormium-ktor-di",
    "kormium-ktor-koin",
    "kormium-bom",
)

subprojects {
    if (name !in publishableModules) return@subprojects

    apply(plugin = "com.vanniktech.maven.publish")

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        signAllPublications()
        coordinates(group.toString(), name, version.toString())

        pom {
            name.set("kormium")
            description.set("Kormium — a simple Kotlin Multiplatform ORM (Postgres + SQLite, JVM + Native).")
            inceptionYear.set("2024")
            url.set("https://github.com/kormium/kormium")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
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
                url.set("https://github.com/kormium/kormium")
                connection.set("scm:git:https://github.com/kormium/kormium.git")
                developerConnection.set("scm:git:ssh://git@github.com/kormium/kormium.git")
            }
        }
    }
}
