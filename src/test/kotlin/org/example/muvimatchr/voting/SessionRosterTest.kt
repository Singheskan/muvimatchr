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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

// Task 1: covers the read model (computeRoster) over live participant/vote tables. Task 2 will
// extend this same file with MockMvc endpoint cases. In-process fixtures throughout -- no HTTP, no
// catalog mock server -- extends PostgresTestSupport directly, matching MatchAggregationServiceTest.
class SessionRosterTest : PostgresTestSupport() {

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

    // Deterministic idleness: backdate the row directly rather than sleeping past the real
    // (production-default, 60s) inactivity window. Matches MatchAggregationServiceTest's convention.
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
    fun `three participants with differing progress produce a join-ordered roster with correct votedCount, finished and active flags`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        val c = newParticipant(session, "Carol")
        pinDeck(session, listOf(680L, 155L, 550L))

        for (movieId in listOf(680L, 155L, 550L)) {
            like(session.id!!, a.id!!, movieId)
        }
        like(session.id!!, b.id!!, 680L)
        // c casts zero votes.

        val roster = matchAggregationService.computeRoster(session.id!!)

        assertEquals(session.id, roster.sessionId)
        assertEquals(3, roster.deckSize)
        assertEquals(3, roster.participants.size)

        val (pa, pb, pc) = roster.participants
        assertEquals(a.id, pa.participantId)
        assertEquals("Alice", pa.displayName)
        assertEquals(3, pa.votedCount)
        assertTrue(pa.isFinished)
        assertTrue(pa.isActive)

        assertEquals(b.id, pb.participantId)
        assertEquals("Bob", pb.displayName)
        assertEquals(1, pb.votedCount)
        assertFalse(pb.isFinished)
        assertTrue(pb.isActive)

        assertEquals(c.id, pc.participantId)
        assertEquals("Carol", pc.displayName)
        assertEquals(0, pc.votedCount)
        assertFalse(pc.isFinished)
        assertTrue(pc.isActive)
    }

    @Test
    fun `a participant backdated past the inactivity window is still present in the roster, marked inactive, with an accurate votedCount`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        pinDeck(session, listOf(550L))

        like(session.id!!, a.id!!, 550L)
        like(session.id!!, b.id!!, 550L)
        backdateLastVote(b.id!!)

        val roster = matchAggregationService.computeRoster(session.id!!)

        assertEquals(2, roster.participants.size, "the idle participant must still appear in the roster, never dropped")
        val bRow = roster.participants.first { it.participantId == b.id }
        assertFalse(bRow.isActive, "backdated past the inactivity window must be marked inactive")
        assertEquals(1, bRow.votedCount, "votedCount must still be reported accurately for an inactive participant")
    }

    @Test
    fun `computeRoster's active id set matches computeStatus's active id set on the same fixture`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        val c = newParticipant(session, "Carol")
        pinDeck(session, listOf(550L))

        like(session.id!!, a.id!!, 550L)
        like(session.id!!, b.id!!, 550L)
        backdateLastVote(b.id!!)
        backdateJoinedAt(c.id!!)
        // c has cast zero votes and its created_at is backdated past the window -- inactive.

        val status = matchAggregationService.computeStatus(session.id!!)
        val roster = matchAggregationService.computeRoster(session.id!!)

        val activeIdsFromRoster = roster.participants.filter { it.isActive }.map { it.participantId }.toSet()
        assertEquals(status.activeCount, activeIdsFromRoster.size, "the active set size must agree between computeStatus and computeRoster")
        assertEquals(setOf(a.id), activeIdsFromRoster, "only Alice remains within the inactivity window")
    }

    @Test
    fun `on a never-pinned session every participant reports finished false including a zero-vote participant`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        newParticipant(session, "Bob")
        // No pinDeck call -- deckSize stays 0.

        // A vote cannot actually be recorded against an unpinned session through the normal flow,
        // but computeRoster must not assume a vote implies a pinned deck: even a participant with
        // zero votes must never report finished on a never-pinned session.
        val roster = matchAggregationService.computeRoster(session.id!!)

        assertEquals(0, roster.deckSize)
        assertEquals(2, roster.participants.size)
        assertTrue(roster.participants.none { it.isFinished }, "a never-pinned session is never finished for anyone")
        assertTrue(roster.participants.any { it.participantId == a.id && it.votedCount == 0 })
    }

    @Test
    fun `the roster is ordered by participant created_at ascending and stable across repeated calls`() {
        val session = newSession()
        val a = newParticipant(session, "Alice")
        val b = newParticipant(session, "Bob")
        val c = newParticipant(session, "Carol")
        pinDeck(session, listOf(550L))

        val first = matchAggregationService.computeRoster(session.id!!)
        val second = matchAggregationService.computeRoster(session.id!!)

        assertEquals(listOf(a.id, b.id, c.id), first.participants.map { it.participantId })
        assertEquals(first, second, "two calls with no intervening writes must return an identical roster")
    }

    @Test
    fun `a session id that does not exist raises the same 404 ResponseStatusException computeStatus raises`() {
        val missingSessionId = UUID.randomUUID()

        val statusEx = assertThrows(ResponseStatusException::class.java) {
            matchAggregationService.computeStatus(missingSessionId)
        }
        val rosterEx = assertThrows(ResponseStatusException::class.java) {
            matchAggregationService.computeRoster(missingSessionId)
        }

        assertEquals(statusEx.statusCode, rosterEx.statusCode)
    }
}
