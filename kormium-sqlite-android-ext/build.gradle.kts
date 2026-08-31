plugins {
    id("com.android.library")
}

repositories {
    google()
    mavenCentral()
}

// The JNI shim that lets Kormium install SQLite extensions on Android — nothing but ~40 lines of C.
//
// It lives in its own module because AGP's Kotlin-Multiplatform library plugin (which kormium-sqlite
// uses) has no NDK support at all: no externalNativeBuild, no CMake, no ndk block. The classic
// `com.android.library` plugin does, so the native half is built here and consumed by
// kormium-sqlite's android target, which packages the .so into the consuming app.
//
// There is no Kotlin here on purpose: the `external fun` that binds to this library is declared in
// kormium-sqlite (SqliteAndroidRegistrationScope), and JNI resolves it by name at runtime, so the
// two halves do not need to sit in the same artifact.
android {
    namespace = "io.github.kormium.sqlite.android.ext"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        externalNativeBuild {
            cmake {
                // The shim only calls dlopen/dlsym, so it needs no C++ runtime and no STL.
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
