package org.example.muvimatchr.voting

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

// In-process fixtures throughout -- no HTTP, no catalog mock server. This suite tests the
// aggregation read model (MatchAggregationService.computeStatus), not the controller or deck
// endpoint, so it extends PostgresTestSupport directly rather than TmdbMockServerSupport.
class MatchAggregationServiceTest : PostgresTestSupport() {

    @Autowired
    lateinit var sessionRepository: SessionRepository

    @Autowired
    lateinit var participantRepository: ParticipantRepository

    @Autowired
    lateinit var sessionService: SessionService

    @Autowired
    lateinit var voteRepository: VoteRepository

    @Autowired
    lateinit var matchAggregationService: MatchAggregationService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

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

    private fun pinDeck(session: Session, movieIds: List<Long>): Session =
        sessionService.pinDeck(session.id!!, null, movieIds.map { cachedMovie(it) })

    private fun like(sessionId: UUID, participantId: UUID, movieId: Long) =
        voteRepository.upsertVote(UUID.randomUUID(), sessionId, participantId, movieId, VoteChoice.LIKE.name)

    private fun pass(sessionId: UUID, participantId: UUID, movieId: Long) =
        voteRepository.upsertVote(UUID.randomUUID(), sessionId, participantId, movieId, VoteChoice.PASS.name)

    // Deterministic idleness: backdate the row directly rather than sleeping past the real
    // (production-default, 60s) inactivity window.
    private fun backdateLastVote(participantId: UUID) {
        jdbcTemplate.update(
            "UPDATE vote SET voted_at = now() - interval '5 minutes' WHERE participant_id = ?",
            participantId,
        )
    }

    private fun backdateJoinedAt(participantId: UUID) {
        jdbcTemplate.update(
            "UPDATE participant SET created_at = now() - interval '5 minutes' WHERE id = ?",
            participantId,
        )
    }

    @Test
    fun `three participants who all liked every movie in a three-movie deck are complete with all three movies matched`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        val c = newParticipant(session, "Carol")
        pinDeck(session, listOf(680L, 155L, 550L))

        for (p in listOf(a, b, c)) {
            for (movieId in listOf(680L, 155L, 550L)) {
                like(session.id!!, p.id!!, movieId)
            }
        }

        val status = matchAggregationService.computeStatus(session.id!!)
        assertTrue(status.isComplete)
        assertEquals(listOf(155L, 550L, 680L), status.matchedMovieIds)
    }

    @Test
    fun `a movie liked by two of three active participants is excluded from matchedMovieIds`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        val c = newParticipant(session, "Carol")
        pinDeck(session, listOf(550L))

        like(session.id!!, a.id!!, 550L)
        like(session.id!!, b.id!!, 550L)
        pass(session.id!!, c.id!!, 550L)

        val status = matchAggregationService.computeStatus(session.id!!)
        assertTrue(status.matchedMovieIds.isEmpty(), "550 was liked by two of three -- not unanimous")
    }

    @Test
    fun `isComplete flips from true to false when a late joiner arrives after the others finished, and back to true once they finish`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        pinDeck(session, listOf(550L))

        like(session.id!!, a.id!!, 550L)
        like(session.id!!, b.id!!, 550L)

        val beforeJoin = matchAggregationService.computeStatus(session.id!!)
        assertTrue(beforeJoin.isComplete, "both existing participants finished the one-movie deck")

        val c = newParticipant(session, "Carol")
        val afterJoin = matchAggregationService.computeStatus(session.id!!)
        assertFalse(afterJoin.isComplete, "a fresh, unvoted late joiner must flip completion back to false")
        assertEquals(3, afterJoin.activeCount)

        like(session.id!!, c.id!!, 550L)
        val afterFinish = matchAggregationService.computeStatus(session.id!!)
        assertTrue(afterFinish.isComplete, "isComplete must return to true once the late joiner also finishes")
    }

    @Test
    fun `a participant backdated past the inactivity window is excluded from activeCount and not required for unanimity`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        pinDeck(session, listOf(550L))

        like(session.id!!, a.id!!, 550L)
        like(session.id!!, b.id!!, 550L)
        backdateLastVote(b.id!!)

        val status = matchAggregationService.computeStatus(session.id!!)
        assertEquals(1, status.activeCount, "the backdated participant must drop out of the active roster")
        assertEquals(listOf(550L), status.matchedMovieIds, "550 was liked by every remaining active participant")
        assertTrue(status.isComplete, "completion is computed over the remaining active participant only")
    }

    @Test
    fun `an idle participant who casts one new vote is immediately back in activeCount and the unanimity requirement`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        pinDeck(session, listOf(550L))

        like(session.id!!, a.id!!, 550L)
        like(session.id!!, b.id!!, 550L)
        backdateLastVote(b.id!!)

        val whileIdle = matchAggregationService.computeStatus(session.id!!)
        assertEquals(1, whileIdle.activeCount)

        // A new vote (upsert on an already-liked movie is fine -- what matters is a fresh voted_at).
        like(session.id!!, b.id!!, 550L)

        val afterVote = matchAggregationService.computeStatus(session.id!!)
        assertEquals(2, afterVote.activeCount, "casting one new vote must re-include the participant on the very next call")
        assertEquals(listOf(550L), afterVote.matchedMovieIds, "the re-included participant must count toward unanimity again")
    }

    @Test
    fun `a zero-vote participant backdated past the window on created_at is excluded, one within the window is included`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val idle = newParticipant(session, "Idle")
        val fresh = newParticipant(session, "Fresh")
        pinDeck(session, listOf(550L))

        like(session.id!!, a.id!!, 550L)
        backdateJoinedAt(idle.id!!)
        // `fresh` has cast no votes and joined within the window (default createdAt = now()).

        val status = matchAggregationService.computeStatus(session.id!!)
        assertEquals(2, status.activeCount, "idle (backdated, zero votes) excluded; alice and fresh included")
    }

    @Test
    fun `an idle participant's earlier likes still appear in likeCounts at full count while excluded from activeCount`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        pinDeck(session, listOf(550L))

        like(session.id!!, a.id!!, 550L)
        like(session.id!!, b.id!!, 550L)
        backdateLastVote(b.id!!)

        val status = matchAggregationService.computeStatus(session.id!!)
        assertEquals(1, status.activeCount, "b is idle and excluded from the active roster")
        assertEquals(1, status.likeCounts.size)
        assertEquals(550L, status.likeCounts.first().movieId)
        assertEquals(2, status.likeCounts.first().likeCount, "b's earlier like must still count toward the tally")
    }

    @Test
    fun `with every participant idle, activeCount is zero, matchedMovieIds is empty, isComplete is false, and likeCounts still reports every like`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        pinDeck(session, listOf(550L))

        like(session.id!!, a.id!!, 550L)
        like(session.id!!, b.id!!, 550L)
        backdateLastVote(a.id!!)
        backdateLastVote(b.id!!)

        val status = matchAggregationService.computeStatus(session.id!!)
        assertEquals(0, status.activeCount)
        assertTrue(status.matchedMovieIds.isEmpty(), "an empty active roster never makes every movie unanimous")
        assertFalse(status.isComplete)
        assertEquals(1, status.likeCounts.size)
        assertEquals(2, status.likeCounts.first().likeCount, "likeCounts is never filtered by activity")
    }

    @Test
    fun `a session with a pinned deck and no votes reports empty likeCounts and empty matchedMovieIds`() {
        val session = newSession()
        newParticipant(session, "Alice")
        pinDeck(session, listOf(550L, 155L))

        val status = matchAggregationService.computeStatus(session.id!!)
        assertTrue(status.likeCounts.isEmpty())
        assertTrue(status.matchedMovieIds.isEmpty())
    }

    @Test
    fun `likeCounts orders by count descending then movieId ascending, and repeated calls are identical`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        val c = newParticipant(session, "Carol")
        pinDeck(session, listOf(155L, 550L, 680L))

        // 680: 3 likes, 155: 2 likes, 550: 2 likes -- 155 and 550 tie, ordered ascending by movieId.
        like(session.id!!, a.id!!, 680L)
        like(session.id!!, b.id!!, 680L)
        like(session.id!!, c.id!!, 680L)
        like(session.id!!, a.id!!, 155L)
        like(session.id!!, b.id!!, 155L)
        like(session.id!!, a.id!!, 550L)
        like(session.id!!, b.id!!, 550L)

        val first = matchAggregationService.computeStatus(session.id!!)
        val second = matchAggregationService.computeStatus(session.id!!)

        val expected = listOf(
            MovieLikeCount(680L, 3),
            MovieLikeCount(155L, 2),
            MovieLikeCount(550L, 2),
        )
        assertEquals(expected, first.likeCounts)
        assertEquals(first.likeCounts, second.likeCounts, "two calls with no intervening writes must return identical lists")
    }

    @Test
    fun `a pinned-deck movie that nobody liked is absent from likeCounts`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        pinDeck(session, listOf(550L, 155L))

        like(session.id!!, a.id!!, 550L)
        // 155 is in the deck but nobody liked it.

        val status = matchAggregationService.computeStatus(session.id!!)
        assertEquals(1, status.likeCounts.size)
        assertEquals(550L, status.likeCounts.first().movieId)
    }

    @Test
    fun `two calls to computeStatus with no intervening writes return identical matchedMovieIds`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        pinDeck(session, listOf(155L, 550L))

        like(session.id!!, a.id!!, 155L)
        like(session.id!!, b.id!!, 155L)
        like(session.id!!, a.id!!, 550L)
        like(session.id!!, b.id!!, 550L)

        val first = matchAggregationService.computeStatus(session.id!!)
        val second = matchAggregationService.computeStatus(session.id!!)

        assertEquals(listOf(155L, 550L), first.matchedMovieIds)
        assertEquals(first.matchedMovieIds, second.matchedMovieIds)
    }
}
