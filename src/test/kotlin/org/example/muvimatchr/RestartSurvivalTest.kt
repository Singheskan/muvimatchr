package org.example.muvimatchr

import org.example.muvimatchr.catalog.MovieCatalogService.CachedMovie
import org.example.muvimatchr.session.Participant
import org.example.muvimatchr.session.ParticipantRepository
import org.example.muvimatchr.session.Session
import org.example.muvimatchr.session.SessionRepository
import org.example.muvimatchr.session.SessionService
import org.example.muvimatchr.voting.MatchAggregationService
import org.example.muvimatchr.voting.Vote
import org.example.muvimatchr.voting.VoteChoice
import org.example.muvimatchr.voting.VoteRepository
import org.example.muvimatchr.voting.VoteService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

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
            .save(Participant(session = session, displayName = "Restart Tester", tokenHash = UUID.randomUUID().toString()))
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

    // Closes the gap the test above does not cover: that test writes its vote via a bare
    // repository save. This one writes through VoteService.recordVote -- the locked, transactional
    // service path -- and additionally proves the pinned-deck JSONB snapshot round-trips intact,
    // closing VOTE-02's "survives disconnect/refresh/restart" clause at the service layer (Phase 1
    // only proved it at the repository layer).
    @Test
    fun `a vote written through VoteService and the pinned deck it targeted both survive a restart`() {
        // --- Context #1: write via the real service paths ---
        val context1 = startContext()

        val session = context1.getBean(SessionRepository::class.java)
            .save(Session(joinCode = "RESTRT"))
        val sessionId = session.id!!

        val participant = context1.getBean(ParticipantRepository::class.java)
            .save(
                Participant(
                    session = session,
                    displayName = "Service Voter",
                    tokenHash = UUID.randomUUID().toString(),
                )
            )
        val participantId = participant.id!!

        val deckMovies = listOf(
            CachedMovie(
                tmdbId = 680L,
                title = "Pulp Fiction",
                posterPath = null,
                genreIds = emptyList(),
                voteAverage = 8.5,
                releaseDate = "1994-10-14",
                overview = null,
            ),
            CachedMovie(
                tmdbId = 155L,
                title = "The Dark Knight",
                posterPath = null,
                genreIds = emptyList(),
                voteAverage = 9.0,
                releaseDate = "2008-07-18",
                overview = null,
            ),
        )
        context1.getBean(SessionService::class.java).pinDeck(sessionId, null, deckMovies)

        // The transactional, locked recordVote path -- not a bare repository save -- is what this
        // test's durability claim is about.
        val statusBeforeRestart = context1.getBean(VoteService::class.java)
            .recordVote(sessionId, participantId, 680L, VoteChoice.LIKE)

        val historyCountBeforeRestart = context1.getBean(JdbcTemplate::class.java)
            .queryForObject("SELECT count(*) FROM flyway_schema_history", Int::class.java)!!
        context1.close()
        activeContext = null // already closed; avoid double-close in @AfterEach

        // --- Context #2: fresh, same container, read back ---
        val context2 = startContext()

        val voteRow = context2.getBean(JdbcTemplate::class.java).queryForMap(
            "SELECT movie_id, choice FROM vote WHERE session_id = ? AND participant_id = ?",
            sessionId,
            participantId,
        )
        assertEquals(680L, (voteRow["movie_id"] as Number).toLong())
        assertEquals(VoteChoice.LIKE.name, voteRow["choice"])

        val foundSession = context2.getBean(SessionRepository::class.java).findById(sessionId).get()
        assertNotNull(foundSession.deckPinnedAt, "deck_pinned_at must survive the restart")
        val sessionService2 = context2.getBean(SessionService::class.java)
        assertEquals(
            listOf(680L, 155L),
            sessionService2.pinnedMovies(foundSession).map { it.tmdbId },
            "the pinned-deck JSONB snapshot must round-trip with the same movie ids in the same order",
        )

        val statusAfterRestart = context2.getBean(MatchAggregationService::class.java).computeStatus(sessionId)
        assertEquals(statusBeforeRestart.deckSize, statusAfterRestart.deckSize)
        assertEquals(statusBeforeRestart.finishedCount, statusAfterRestart.finishedCount)
        assertEquals(statusBeforeRestart.isComplete, statusAfterRestart.isComplete)

        val historyCountAfterRestart = context2.getBean(JdbcTemplate::class.java)
            .queryForObject("SELECT count(*) FROM flyway_schema_history", Int::class.java)!!
        assertEquals(historyCountBeforeRestart, historyCountAfterRestart)
    }
}
