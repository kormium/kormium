@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

import io.github.kormium.autocommit
import io.github.kormium.database.createDatabase
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Long-running stability / soak tests. Opt-in (tagged "stability") so they don't slow the
 * normal build: run with `./gradlew :kormium-postgres:jvmTest -Pstability`.
 */
@Tag("stability")
class StabilityTest {

    private fun assumeDocker() =
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")

    /** Many threads hammering the pool for a while must complete with no errors. */
    @Test
    fun sustainedConcurrentLoad() {
        assumeDocker()
        val errors = ConcurrentLinkedQueue<Throwable>()
        ItDatabase.newDriver(poolSize = 8).use { driver ->
            val threads = (1..16).map {
                Thread {
                    try {
                        repeat(2_000) { check(driver.autocommit { execute("SELECT 1", params = emptyMap(), invalidates = emptyList()) { rs -> rs.getInt(0) } }.single() == 1) }
                    } catch (t: Throwable) {
                        errors += t
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }
        assertTrue(errors.isEmpty(), "errors under sustained load: ${errors.firstOrNull()}")
    }

    /** With a pool of 2, a leaked connection would exhaust the pool and hang; 2000 ops must pass. */
    @Test
    fun noConnectionLeakUnderManyOps() {
        assumeDocker()
        ItDatabase.newDriver(poolSize = 2).use { driver ->
            repeat(2_000) { check(driver.autocommit { execute("SELECT 1", params = emptyMap(), invalidates = emptyList()) { rs -> rs.getInt(0) } }.single() == 1) }
        }
    }

    /**
     * After the database restarts, the pool self-heals and queries succeed again. Uses its
     * own container (not the shared one) so restarting it can't disturb the other tests.
     */
    @Test
    fun poolRecoversAfterDatabaseRestart() {
        assumeDocker()
        val container = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        try {
            createDatabase(
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
                poolSize = 4,
            ).use { driver ->
                check(driver.autocommit { execute("SELECT 1", params = emptyMap(), invalidates = emptyList()) { rs -> rs.getInt(0) } }.single() == 1)
                container.dockerClient.restartContainerCmd(container.containerId).exec()
                val deadline = System.currentTimeMillis() + 60_000
                var recovered = false
                while (System.currentTimeMillis() < deadline && !recovered) {
                    recovered = runCatching { driver.autocommit { execute("SELECT 1", params = emptyMap(), invalidates = emptyList()) { rs -> rs.getInt(0) } }.single() == 1 }
                        .getOrDefault(false)
                    if (!recovered) Thread.sleep(500)
                }
                assertTrue(recovered, "pool did not recover after database restart")
            }
        } finally {
            container.stop()
        }
    }
}
