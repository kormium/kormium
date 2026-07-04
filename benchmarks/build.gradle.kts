import groovy.json.JsonSlurper
import java.time.LocalDate
import java.util.Locale

plugins {
    kotlin("jvm")
    id("me.champeau.jmh") version "0.7.3"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    jmhImplementation(project(":kormium-postgres"))
    jmhImplementation("org.testcontainers:postgresql:1.21.3")
    jmhImplementation("org.postgresql:postgresql:42.7.7")
    jmhImplementation(project(":kormium-decimal"))
    jmhImplementation("com.zaxxer:HikariCP:6.3.0")

    // For the cross-ORM comparison benchmark.
    jmhImplementation("org.hibernate.orm:hibernate-core:7.0.2.Final")
    jmhImplementation("org.hibernate.orm:hibernate-hikaricp:7.0.2.Final")
    jmhImplementation("org.jetbrains.exposed:exposed-core:1.0.0-beta-4")
    jmhImplementation("org.jetbrains.exposed:exposed-jdbc:1.0.0-beta-4")
}

val resultsJson = layout.buildDirectory.file("results/jmh/results.json")
val nativeJson = layout.buildDirectory.file("results/jmh/native.json")
val summaryMd = layout.buildDirectory.file("results/jmh/summary.md")

jmh {
    jmhVersion.set("1.37")
    // Warmup/measurement/fork counts live on the benchmark classes themselves so a plain
    // `jmh` run and a manual `java -jar benchmarks-*-jmh.jar` run measure the same thing.
    // `-Pbench.quick` deliberately overrides them for a fast, indicative-only run.
    includes.add((findProperty("bench.filter") as String?) ?: "ComparisonBenchmark")
    if (hasProperty("bench.quick")) {
        fork.set(1)
        warmupIterations.set(1)
        warmup.set("1s")
        iterations.set(2)
        timeOnIteration.set("1s")
    }
    // External database coordinates (used by benchmarks/run.sh to share one Postgres with
    // the native harness). Passed as system properties because environment variables do not
    // reliably reach the forked JMH JVMs through a long-lived Gradle daemon.
    for (key in listOf("host", "port", "name", "user", "password")) {
        (findProperty("bench.db.$key") as String?)?.let { jvmArgsAppend.add("-Dkormium.db.$key=$it") }
    }
    resultFormat.set("JSON")
    resultsFile.set(resultsJson)
}

// Renders build/results/jmh/results.json (plus native.json from benchmarks/run.sh, when
// present) as a per-operation comparison table: printed to the console after every `jmh`
// run and written to summary.md for copy-pasting.
val benchmarkSummary = tasks.register("benchmarkSummary") {
    group = "benchmark"
    description = "Prints a comparison table from the latest JMH (and native) results"
    val jsonFile = resultsJson
    val nativeFile = nativeJson
    val mdFile = summaryMd
    doLast {
        val json = jsonFile.get().asFile
        if (!json.exists()) {
            println("No JMH results at $json — run `./gradlew :benchmarks:jmh` first.")
            return@doLast
        }

        fun fmt(score: Double, err: Double = Double.NaN): String =
            String.format(Locale.ROOT, "%,.0f", score) +
                if (err.isNaN()) "" else String.format(Locale.ROOT, " ± %,.0f", err)

        val orms = listOf("kormium", "exposed", "hibernate")
        val cells = mutableMapOf<Pair<String, String>, String>() // (operation, column) -> "score ± err"
        val extras = mutableListOf<Pair<String, String>>()       // non-comparison benchmarks
        var units = "ops/s"

        @Suppress("UNCHECKED_CAST")
        val results = JsonSlurper().parse(json) as List<Map<String, Any?>>
        for (r in results) {
            val bench = r["benchmark"] as String
            val method = bench.substringAfterLast('.')
            val clazz = bench.substringBeforeLast('.').substringAfterLast('.')
            val metric = r["primaryMetric"] as Map<*, *>
            val score = (metric["score"] as Number).toDouble()
            val err = (metric["scoreError"] as? Number)?.toDouble() ?: Double.NaN
            units = metric["scoreUnit"] as? String ?: units
            val orm = orms.firstOrNull { method.startsWith(it) }
            if (clazz == "ComparisonBenchmark" && orm != null) {
                val op = method.removePrefix(orm).replaceFirstChar { it.lowercaseChar() }
                cells[op to orm] = fmt(score, err)
            } else {
                extras += "$clazz.$method" to fmt(score, err)
            }
        }

        // Results of the native harness (single run, no error estimate).
        val native = nativeFile.get().asFile.takeIf { it.exists() }?.let {
            @Suppress("UNCHECKED_CAST")
            (JsonSlurper().parse(it) as Map<String, Any?>)
        }.orEmpty()
        for ((op, score) in native) cells[op to "native"] = fmt((score as Number).toDouble())

        val columns = buildList {
            add("kormium" to "Kormium JVM")
            if (native.isNotEmpty()) add("native" to "Kormium Native")
            add("exposed" to "Exposed")
            add("hibernate" to "Hibernate")
        }
        val ops = (listOf("findById", "selectWhere", "selectMany", "insert", "batchInsert", "updateById") +
            cells.keys.map { it.first })
            .distinct().filter { op -> columns.any { (key, _) -> (op to key) in cells } }

        val lines = mutableListOf<String>()
        if (ops.isNotEmpty()) {
            val opW = (ops + "Operation").maxOf { it.length }
            val colWs = columns.map { (key, title) ->
                (ops.map { cells[it to key] ?: "—" } + title).maxOf { it.length }
            }
            lines += "Operation".padEnd(opW) + columns.indices.joinToString("") { "  " + columns[it].second.padStart(colWs[it]) }
            lines += "-".repeat(lines[0].length)
            for (op in ops) {
                lines += op.padEnd(opW) + columns.indices.joinToString("") { "  " + (cells[op to columns[it].first] ?: "—").padStart(colWs[it]) }
            }
        }
        for ((name, cell) in extras) lines += "$name: $cell"

        val title = "Benchmark summary — $units, higher is better"
        println()
        println(title)
        println("═".repeat(title.length))
        lines.forEach(::println)
        println()
        println("Full JMH results: $json")

        mdFile.get().asFile.writeText(buildString {
            appendLine("# Benchmark summary")
            appendLine()
            appendLine("JMH, $units, higher is better. Generated ${LocalDate.now()}.")
            appendLine()
            if (ops.isNotEmpty()) {
                appendLine("| Operation | " + columns.joinToString(" | ") { it.second } + " |")
                appendLine("| --- | " + columns.joinToString(" | ") { "---:" } + " |")
                for (op in ops) {
                    appendLine("| `$op` | " + columns.joinToString(" | ") { cells[op to it.first] ?: "—" } + " |")
                }
            }
            for ((name, cell) in extras) appendLine("- `$name`: $cell")
            appendLine()
            appendLine("Numbers are relative — see `benchmarks/README.md` for methodology and caveats.")
        })
        println("Markdown summary:  ${mdFile.get().asFile}")
    }
}

tasks.named("jmh") { finalizedBy(benchmarkSummary) }
