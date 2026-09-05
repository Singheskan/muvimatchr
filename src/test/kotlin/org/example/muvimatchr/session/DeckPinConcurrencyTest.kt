package org.example.muvimatchr.session

import com.zaxxer.hikari.HikariDataSource
import org.example.muvimatchr.catalog.MovieCatalogService.CachedMovie
import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

// WR-03: mirrors VoteServiceConcurrencyTest's structure -- N threads released by a single
// CountDownLatch starting gun (never a fixed-duration sleep), repeated across several
// independently created sessions, with an explicit pool-size sanity check ruling out the
// connection pool as an accidental serialiser before trusting anything else the race proves.
// This proves CR-01's fix: pinDeck's own doc comment claims "first writer wins... a second call
// is a no-op that returns the session unchanged," and prior to CR-01 nothing in the implementation
// enforced that -- two concurrent first-time pin attempts could both read deckPinnedAt == null
// before either committed.
class DeckPinConcurrencyTest : PostgresTestSupport() {

    @Autowired
    lateinit var sessionRepository: SessionRepository

    @Autowired
    lateinit var sessionService: SessionService

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private val racingThreadCount = 3

    // Postgres's jsonb column type does not preserve object-key order or whitespace on round-trip
    // (Postgres docs: "the jsonb data type does not preserve white space, does not preserve the
    // order of object keys"). A caller's own in-process return value (the exact string Jackson just
    // serialized, never round-tripped through the column) is therefore NOT expected to be
    // byte-for-byte identical to what a later `findById` reads back from the database, even when
    // both describe the exact same movies. Comparing the parsed structure -- not the raw JSON text
    // -- is what "the same persisted snapshot" actually means here.
    private fun parseMovies(json: String?): List<CachedMovie> {
        if (json == null) return emptyList()
        return objectMapper.readValue(json, object : TypeReference<List<CachedMovie>>() {})
    }

    private fun newSession(): Session =
        sessionRepository.save(Session(joinCode = UUID.randomUUID().toString().take(16)))

    private fun cachedMovie(movieId: Long): CachedMovie = CachedMovie(
        tmdbId = movieId,
        title = "Movie $movieId",
        posterPath = null,
        genreIds = emptyList(),
        voteAverage = 7.0,
        releaseDate = "2020-01-01",
        overview = null,
    )

    @Test
    fun `ten independent simultaneous first-time deck pins each converge on exactly one persisted snapshot`() {
        // Rule out the pool as the serialiser before trusting anything else the race proves --
        // see VoteServiceConcurrencyTest for the identical rationale.
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

            // Each racing thread proposes a distinct movie list, exactly as distinct concurrent
            // callers would after independently fetching from the catalog -- if the race were not
            // closed, more than one thread's proposal could end up persisted (or a hybrid could
            // result from a lost update).
            val candidateDecks = (0 until racingThreadCount).map { threadIndex ->
                val base = 800_000L + iteration * 100 + threadIndex * 10
                listOf(cachedMovie(base), cachedMovie(base + 1))
            }

            val executor = Executors.newFixedThreadPool(racingThreadCount)
            val startingGun = CountDownLatch(1)
            try {
                val futures: List<Future<Session>> = candidateDecks.map { movies ->
                    executor.submit<Session> {
                        startingGun.await()
                        sessionService.pinDeck(sessionId, null, movies)
                    }
                }
                startingGun.countDown()

                val results = futures.mapIndexed { index, future ->
                    try {
                        future.get(30, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        throw AssertionError(
                            "Iteration $iteration: racing pinDeck call $index threw or timed out: ${e.message}",
                            e,
                        )
                    }
                }

                // First writer wins: every racing call's returned Session must report the exact
                // same persisted movie list -- the "loser" calls no-op and return the winner's
                // already-committed snapshot rather than each thread persisting its own proposal.
                // Compared structurally (parsed movies), not as raw JSON text: jsonb round-trips
                // through Postgres do not preserve object-key order, so the winner's own in-process
                // return value (never round-tripped) is not expected to be byte-identical to a
                // loser's freshly-read value even when both describe the same movies.
                val distinctSnapshots = results.map { parseMovies(it.pinnedDeckJson) }.toSet()
                assertEquals(
                    1, distinctSnapshots.size,
                    "Iteration $iteration: all $racingThreadCount racing pinDeck calls must return the same " +
                        "persisted snapshot, got ${distinctSnapshots.size} distinct snapshots",
                )

                val persisted = sessionRepository.findById(sessionId).get()
                assertTrue(persisted.deckPinnedAt != null, "Iteration $iteration: session must be pinned after the race")
                assertEquals(
                    parseMovies(persisted.pinnedDeckJson), distinctSnapshots.single(),
                    "Iteration $iteration: every racing caller's response must match the canonical persisted " +
                        "snapshot every later reader will be served -- this is CR-01's invariant",
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
