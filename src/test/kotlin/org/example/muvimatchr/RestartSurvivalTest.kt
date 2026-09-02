package org.example.muvimatchr

import org.example.muvimatchr.session.Participant
import org.example.muvimatchr.session.ParticipantRepository
import org.example.muvimatchr.session.Session
import org.example.muvimatchr.session.SessionRepository
import org.example.muvimatchr.voting.Vote
import org.example.muvimatchr.voting.VoteChoice
import org.example.muvimatchr.voting.VoteRepository
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

    // NOTE: SpringApplicationBuilder.properties(...) sets *default* properties (the
    // lowest-precedence property source), so it is silently overridden by the higher-precedence
    // classpath application.properties datasource config — the test would then run against the
    // persistent local dev database instead of the ephemeral Testcontainers instance, defeating
    // D-03 entirely. Command-line-style "--key=value" args passed to .run(...) have the highest
    // Spring Boot property precedence and correctly win over application.properties.
    private fun startContext(): ConfigurableApplicationContext =
        SpringApplicationBuilder(MuviMatchrApplication::class.java)
            .run(
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--server.port=0",
            )
            .also { activeContext = it }

    @Test
    fun `session, participant and vote written before restart are readable after a fresh context starts`() {
        // --- Context #1: write ---
        val context1 = startContext()

        val session = context1.getBean(SessionRepository::class.java)
            .save(Session(joinCode = "ABC123"))
        val sessionId = session.id!!

        val participant = context1.getBean(ParticipantRepository::class.java)
            .save(Participant(session = session, displayName = "Restart Tester"))
        val participantId = participant.id!!

        val vote = context1.getBean(VoteRepository::class.java)
            .save(Vote(session = session, participant = participant, movieId = 603L, choice = VoteChoice.LIKE))
        val voteId = vote.id!!

        val historyCountBeforeRestart = context1.getBean(JdbcTemplate::class.java)
            .queryForObject("SELECT count(*) FROM flyway_schema_history", Int::class.java)!!
        context1.close()
        activeContext = null // already closed; avoid double-close in @AfterEach

        // --- Context #2: fresh, same container, read back ---
        val context2 = startContext()

        val foundSession = context2.getBean(SessionRepository::class.java).findById(sessionId)
        assertTrue(foundSession.isPresent)
        assertEquals("ABC123", foundSession.get().joinCode)

        val foundParticipant = context2.getBean(ParticipantRepository::class.java).findById(participantId)
        assertTrue(foundParticipant.isPresent)
        assertEquals("Restart Tester", foundParticipant.get().displayName)
        // Lazy association navigation — proves the FK and mapping survived the restart,
        // not just the flat scalar columns.
        assertEquals(sessionId, foundParticipant.get().session.id)

        val foundVote = context2.getBean(VoteRepository::class.java).findById(voteId)
        assertTrue(foundVote.isPresent)
        assertEquals(603L, foundVote.get().movieId)
        assertEquals(VoteChoice.LIKE, foundVote.get().choice)

        val historyCountAfterRestart = context2.getBean(JdbcTemplate::class.java)
            .queryForObject("SELECT count(*) FROM flyway_schema_history", Int::class.java)!!
        assertEquals(historyCountBeforeRestart, historyCountAfterRestart)
    }
}
