package org.example.muvimatchr

import org.example.muvimatchr.session.Session
import org.example.muvimatchr.session.SessionRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers
class RestartSurvivalTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18")
    }

    private var activeContext: ConfigurableApplicationContext? = null

    @AfterEach
    fun tearDown() {
        activeContext?.close()
        activeContext = null
    }

    private fun startContext(): ConfigurableApplicationContext =
        SpringApplicationBuilder(MuviMatchrApplication::class.java)
            .properties(
                "spring.datasource.url=${postgres.jdbcUrl}",
                "spring.datasource.username=${postgres.username}",
                "spring.datasource.password=${postgres.password}",
                "spring.jpa.hibernate.ddl-auto=validate",
                "server.port=0",
            )
            .run()
            .also { activeContext = it }

    @Test
    fun `session written before restart is readable after a fresh context starts`() {
        // --- Context #1: write ---
        val context1 = startContext()
        val sessionId = context1.getBean(SessionRepository::class.java)
            .save(Session(joinCode = "ABC123"))
            .id!!
        val historyCountBeforeRestart = context1.getBean(JdbcTemplate::class.java)
            .queryForObject("SELECT count(*) FROM flyway_schema_history", Int::class.java)!!
        context1.close()
        activeContext = null // already closed; avoid double-close in @AfterEach

        // --- Context #2: fresh, same container, read back ---
        val context2 = startContext()
        val found = context2.getBean(SessionRepository::class.java).findById(sessionId)

        assertTrue(found.isPresent)
        assertEquals("ABC123", found.get().joinCode)

        val historyCountAfterRestart = context2.getBean(JdbcTemplate::class.java)
            .queryForObject("SELECT count(*) FROM flyway_schema_history", Int::class.java)!!
        assertEquals(historyCountBeforeRestart, historyCountAfterRestart)
    }
}
