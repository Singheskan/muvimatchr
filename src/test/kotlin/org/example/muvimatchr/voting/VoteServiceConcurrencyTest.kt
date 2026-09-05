package org.example.muvimatchr.voting

import com.zaxxer.hikari.HikariDataSource
import org.example.muvimatchr.catalog.MovieCatalogService.CachedMovie
import org.example.muvimatchr.session.Participant
import org.example.muvimatchr.session.ParticipantRepository
import org.example.muvimatchr.session.Session
import org.example.muvimatchr.session.SessionRepository
import org.example.muvimatchr.session.SessionService
import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

// This is the failure that ended the prior prototype (04-PLAN.md objective). A two-thread test
// that merely happens to overlap can pass for the wrong reason (04-RESEARCH.md Pitfall C): this
// test releases a fixed number of threads from a single CountDownLatch starting gun -- never a
// fixed-duration sleep -- repeats the race across ten independently created sessions, and rules
// out the connection pool as an accidental serialiser before trusting anything else the race
// proves.
class VoteServiceConcurrencyTest : PostgresTestSupport() {

    @Autowired
    lateinit var sessionRepository: SessionRepository

    @Autowired
    lateinit var participantRepository: ParticipantRepository

    @Autowired
    lateinit var sessionService: SessionService

    @Autowired
    lateinit var voteService: VoteService

    @Autowired
    lateinit var matchAggregationService: MatchAggregationService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var dataSource: DataSource

    private val racingParticipantNames = listOf("Alice", "Bob", "Carol")
    private val racingThreadCount = racingParticipantNames.size

    private fun newSession(): Session =
        sessionRepository.save(Session(joinCode = UUID.randomUUID().toString().take(16)))

    private fun newParticipant(session: Session, displayName: String): Participant =
        participantRepository.save(
            Participant(session = session, displayName = displayName, tokenHash = UUID.randomUUID().toString())
        )

    private fun cachedMovie(movieId: Long): CachedMovie = CachedMovie(
        tmdbId = movieId,
        title = "Movie $movieId",
        posterPath = null,
        genreIds = emptyList(),
        voteAverage = 7.0,
        releaseDate = "2020-01-01",
        overview = null,
    )

    private fun voteCountFor(sessionId: UUID, movieId: Long): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vote WHERE session_id = ? AND movie_id = ?",
            Int::class.java,
            sessionId,
            movieId,
        )!!

    @Test
    fun `ten independent simultaneous three-way finishes each persist every vote and complete exactly once`() {
        // Rule out the pool as the serialiser before trusting anything else the race proves: if
        // the pool had fewer connections than racing threads, the threads would queue for a
        // connection rather than contend for Postgres's row lock, and a green result here would
        // certify a lock the test never actually exercised. Asserted once, at the top of the
        // class's only test, so its failure message names pool sizing rather than surfacing as a
        // mysterious downstream timeout.
        val hikari = dataSource as HikariDataSource
        assertTrue(
            hikari.maximumPoolSize >= racingThreadCount + 1,
            "Hikari maximumPoolSize (${hikari.maximumPoolSize}) must exceed the racing thread count " +
                "($racingThreadCount), or the connection pool -- not Postgres's row lock -- would be " +
                "what serialises this race",
        )

        repeat(10) { iteration ->
            val session = newSession()
            val sessionId = session.id!!
            val participants = racingParticipantNames.map { newParticipant(session, it) }
            val firstMovieId = 900_000L + iteration * 10
            val secondMovieId = firstMovieId + 1
            sessionService.pinDeck(sessionId, null, listOf(cachedMovie(firstMovieId), cachedMovie(secondMovieId)))

            // Every participant is one vote short of finishing before the race: all three vote on
            // the first movie sequentially (uncontended), so the race below is exclusively over
            // each participant's final, session-completing vote on the second movie.
            for (p in participants) {
                voteService.recordVote(sessionId, p.id!!, firstMovieId, VoteChoice.LIKE)
            }

            val preRaceStatus = matchAggregationService.computeStatus(sessionId)
            assertFalse(preRaceStatus.isComplete, "Iteration $iteration: session must not be complete before the race")

            val executor = Executors.newFixedThreadPool(racingThreadCount)
            val startingGun = CountDownLatch(1)
            try {
                val futures: List<Future<SessionVoteStatus>> = participants.map { p ->
                    executor.submit<SessionVoteStatus> {
                        startingGun.await()
                        voteService.recordVote(sessionId, p.id!!, secondMovieId, VoteChoice.LIKE)
                    }
                }
                startingGun.countDown()

                val statuses = futures.mapIndexed { index, future ->
                    try {
                        future.get(30, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        throw AssertionError(
                            "Iteration $iteration: racing thread for participant " +
                                "'${racingParticipantNames[index]}' threw or timed out: ${e.message}",
                            e,
                        )
                    }
                }

                val completeCount = statuses.count { it.isComplete }
                assertEquals(
                    1, completeCount,
                    "Iteration $iteration: exactly one of $racingThreadCount racing responses must report " +
                        "isComplete true, got $completeCount",
                )

                assertEquals(
                    racingThreadCount, voteCountFor(sessionId, secondMovieId),
                    "Iteration $iteration: all $racingThreadCount racing votes for movie $secondMovieId must be " +
                        "persisted -- none lost to the race",
                )

                val finalStatus = matchAggregationService.computeStatus(sessionId)
                assertTrue(
                    finalStatus.isComplete,
                    "Iteration $iteration: post-race computeStatus must report the session complete",
                )
                assertEquals(
                    racingThreadCount, finalStatus.finishedCount,
                    "Iteration $iteration: post-race finishedCount must equal the participant count",
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
