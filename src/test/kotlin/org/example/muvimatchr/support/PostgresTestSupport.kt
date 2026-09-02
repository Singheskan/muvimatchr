package org.example.muvimatchr.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.postgresql.PostgreSQLContainer

// NOTE: deliberately NOT @Testcontainers/@Container-managed. That JUnit5 extension's lifecycle
// is per-test-CLASS: with two subclasses of this base (SessionRepositoryTest, VoteRepositoryTest)
// sharing one static container field, it stops the container after the first class's tests finish
// and starts a NEW container (new port) for the second class — while Spring's ApplicationContext
// cache (keyed by this shared @SpringBootTest configuration) reuses the FIRST class's DataSource,
// which still points at the now-dead first container's port. Result: "Connection refused" only when
// running the full suite, not any single test class in isolation. Testcontainers' documented fix for
// a container shared across multiple test classes is the "singleton container" pattern: start it
// once, manually, and let it run until the JVM exits (Ryuk reaper cleans it up) rather than letting
// the per-class extension stop/restart it.
@SpringBootTest
abstract class PostgresTestSupport {

    companion object {
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18").apply { start() }
    }
}
