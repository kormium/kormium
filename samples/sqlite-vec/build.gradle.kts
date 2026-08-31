// A reference third-party SQLite extension package (see ADR 0013). It compiles ONLY
// sqlite-vec, with -DSQLITE_CORE, into its own static library: no SQLite of its own, so the
// sqlite3_* symbols stay unresolved and are satisfied at final link by the libsqlite3.a already
// embedded in kormium-sqlite's cinterop klib. That is what lets extensions compose.
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)

    val cinteropDir = project.file("src/nativeInterop/cinterop")
    val vecSource = cinteropDir.resolve("sqlite-vec.c")
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
        val outDir = layout.buildDirectory.dir("vec/$konanName")
        val objFile = outDir.map { it.file("sqlite-vec.o") }
        val staticLib = outDir.map { it.file("libvec.a") }

        val compileVec = tasks.register<Exec>("compileVec$capName") {
            inputs.file(vecSource)
            outputs.file(objFile)
            doFirst {
                objFile.get().asFile.parentFile.mkdirs()
                // Через response-файл: run_konan съедает аргументы вида -DFOO=1, переданные напрямую.
                fun q(f: File) = "\"" + f.absolutePath.replace('\\', '/') + "\""
                val rsp = objFile.get().asFile.resolveSibling("clang-args.rsp")
                rsp.writeText(
                    listOf(
                        "-O2", "-DSQLITE_CORE=1", "-DSQLITE_VEC_STATIC=1",
                        "-I" + sqliteHeaders.absolutePath, "-I" + cinteropDir.absolutePath,
                        "-c", q(vecSource), "-o", q(objFile.get().asFile),
                    ).joinToString("\n"),
                )
                commandLine(runKonan() + listOf("clang", "clang", konanName, "@" + rsp.absolutePath))
            }
        }
        val archiveVec = tasks.register<Exec>("archiveVec$capName") {
            dependsOn(compileVec)
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
            register("vec") {
                defFile(cinteropDir.resolve("vec.def"))
                compilerOpts("-I${sqliteHeaders.absolutePath}", "-I${cinteropDir.absolutePath}")
                extraOpts("-staticLibrary", "libvec.a", "-libraryPath", outDir.get().asFile.absolutePath)
            }
        }
        tasks.named("cinteropVec$capName").configure {
            dependsOn(archiveVec)
            inputs.file(staticLib)
        }
    }

    sourceSets {
        val linuxX64Main by getting {
            dependencies { implementation(project(":kormium-sqlite")) }
        }
        val linuxX64Test by getting {
            dependencies {
                implementation(kotlin("test"))
                // A second extension in its own module/klib/.a — the additivity check.
                implementation(project(":samples:sqlite-uuid"))
            }
        }
    }
}
