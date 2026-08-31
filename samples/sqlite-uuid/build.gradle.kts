// A second, independent extension package — SQLite's own ext/misc/uuid.c — used together with
// samples/sqlite-vec to show that extensions from different packages compose.
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)

    val cinteropDir = project.file("src/nativeInterop/cinterop")
    val vecSource = cinteropDir.resolve("uuid.c")
    // Заголовки SQLite автор расширения берёт у Kormium (или вендорит сам).
    val sqliteHeaders = rootProject.file("kormium-sqlite/src/nativeInterop/cinterop")

    val konanDataDir = System.getenv("KONAN_DATA_DIR")?.let(::File)
        ?: File(System.getProperty("user.home"), ".konan")
    val konanVersion = "2.4.10"
    fun runKonan(): List<String> {
        val dist = (konanDataDir.listFiles { f ->
            f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") && f.name.endsWith(konanVersion)
        } ?: emptyArray<File>()).firstOrNull()
            ?: error("Kotlin/Native $konanVersion toolchain not found under $konanDataDir")
        return listOf(dist.resolve("bin/run_konan").absolutePath)
    }

    listOf(linuxX64()).forEach { target ->
        val konanName = target.konanTarget.name
        val capName = target.targetName.replaceFirstChar { it.uppercase() }
        val outDir = layout.buildDirectory.dir("uuidext/$konanName")
        val objFile = outDir.map { it.file("uuid.o") }
        val staticLib = outDir.map { it.file("libuuid.a") }

        val compileUuid = tasks.register<Exec>("compileUuid$capName") {
            inputs.file(vecSource)
            outputs.file(objFile)
            doFirst {
                objFile.get().asFile.parentFile.mkdirs()
                // Через response-файл: run_konan съедает аргументы вида -DFOO=1, переданные напрямую.
                fun q(f: File) = "\"" + f.absolutePath.replace('\\', '/') + "\""
                val rsp = objFile.get().asFile.resolveSibling("clang-args.rsp")
                rsp.writeText(
                    listOf(
                        "-O2", "-DSQLITE_CORE=1", "-DSQLITE_OMIT_LOAD_EXTENSION=0",
                        "-I" + sqliteHeaders.absolutePath, "-I" + cinteropDir.absolutePath,
                        "-c", q(vecSource), "-o", q(objFile.get().asFile),
                    ).joinToString("\n"),
                )
                commandLine(runKonan() + listOf("clang", "clang", konanName, "@" + rsp.absolutePath))
            }
        }
        val archiveUuid = tasks.register<Exec>("archiveUuid$capName") {
            dependsOn(compileUuid)
            inputs.file(objFile)
            outputs.file(staticLib)
            doFirst {
                commandLine(
                    runKonan() + listOf(
                        "llvm", "llvm-ar", "rcs",
                        staticLib.get().asFile.absolutePath, objFile.get().asFile.absolutePath,
                    ),
                )
            }
        }

        target.compilations.getByName("main").cinterops {
            register("uuid") {
                defFile(cinteropDir.resolve("uuid.def"))
                compilerOpts("-I${sqliteHeaders.absolutePath}", "-I${cinteropDir.absolutePath}")
                extraOpts("-staticLibrary", "libuuid.a", "-libraryPath", outDir.get().asFile.absolutePath)
            }
        }
        tasks.named("cinteropUuid$capName").configure {
            dependsOn(archiveUuid)
            inputs.file(staticLib)
        }
    }

    sourceSets {
        val linuxX64Main by getting {
            dependencies { implementation(project(":kormium-sqlite")) }
        }
        val linuxX64Test by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}
