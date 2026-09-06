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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.util.UUID

// Task 1: covers the read model (computeRoster) over live participant/vote tables. Task 2 extends
// this same file with MockMvc endpoint cases (@AutoConfigureMockMvc). In-process fixtures
// throughout -- no catalog mock server needed since decks are pinned directly via SessionService,
// not via the HTTP deck endpoint -- extends PostgresTestSupport directly, matching
// MatchAggregationServiceTest.
@AutoConfigureMockMvc
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

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

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

    // ---- Task 2: GET /api/sessions/{sessionId}/votes/roster (membership-gated REST endpoint) ----

    private fun createSessionViaHttp(): Pair<String, String> {
        val response = mockMvc.perform(post("/api/sessions"))
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString
        @Suppress("UNCHECKED_CAST")
        val body = objectMapper.readValue(response, Map::class.java) as Map<String, Any>
        return (body["sessionId"] as String) to (body["joinCode"] as String)
    }

    private fun joinViaHttp(joinCode: String, displayName: String): String {
        val response = mockMvc.perform(
            post("/api/sessions/$joinCode/participants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"$displayName"}""")
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString
        @Suppress("UNCHECKED_CAST")
        val body = objectMapper.readValue(response, Map::class.java) as Map<String, Any>
        return body["token"] as String
    }

    @Test
    fun `GET roster with a valid bearer token for a member returns 200 with sessionId, deckSize and a join-ordered participants array`() {
        val (sessionId, joinCode) = createSessionViaHttp()
        val tokenAlice = joinViaHttp(joinCode, "Alice")
        val tokenBob = joinViaHttp(joinCode, "Bob")
        val session = sessionRepository.findById(UUID.fromString(sessionId)).get()
        pinDeck(session, listOf(550L, 155L))
        val alice = participantRepository.findBySession_IdOrderByCreatedAtAsc(session.id!!).first { it.displayName == "Alice" }
        like(session.id!!, alice.id!!, 550L)

        val responseBody = mockMvc.perform(
            get("/api/sessions/$sessionId/votes/roster")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $tokenAlice")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sessionId").value(sessionId))
            .andExpect(jsonPath("$.deckSize").value(2))
            .andExpect(jsonPath("$.participants[0].displayName").value("Alice"))
            .andExpect(jsonPath("$.participants[1].displayName").value("Bob"))
            .andReturn()
            .response
            .contentAsString

        @Suppress("UNCHECKED_CAST")
        val body = objectMapper.readValue(responseBody, Map::class.java) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val participants = body["participants"] as List<Map<String, Any>>
        assertEquals(2, participants.size)
        assertEquals(tokenBob.isNotBlank(), true) // tokenBob only used to seed a second participant
    }

    @Test
    fun `each roster participant entry serializes with the wire field names participantId, displayName, votedCount, isFinished, isActive`() {
        val (sessionId, joinCode) = createSessionViaHttp()
        val token = joinViaHttp(joinCode, "Alice")
        val session = sessionRepository.findById(UUID.fromString(sessionId)).get()
        pinDeck(session, listOf(550L))
        val alice = participantRepository.findBySession_IdOrderByCreatedAtAsc(session.id!!).first()
        like(session.id!!, alice.id!!, 550L)

        mockMvc.perform(
            get("/api/sessions/$sessionId/votes/roster")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.participants[0].participantId").value(alice.id.toString()))
            .andExpect(jsonPath("$.participants[0].displayName").value("Alice"))
            .andExpect(jsonPath("$.participants[0].votedCount").value(1))
            .andExpect(jsonPath("$.participants[0].isFinished").value(true))
            .andExpect(jsonPath("$.participants[0].isActive").value(true))
    }

    @Test
    fun `GET roster with a token belonging to a different session is rejected with 404`() {
        val (sessionIdA, joinCodeA) = createSessionViaHttp()
        val tokenA = joinViaHttp(joinCodeA, "Alice")
        val (sessionIdB, joinCodeB) = createSessionViaHttp()
        joinViaHttp(joinCodeB, "Bob")

        mockMvc.perform(
            get("/api/sessions/$sessionIdB/votes/roster")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $tokenA")
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `GET roster with no Authorization header is rejected with 401`() {
        val (sessionId, _) = createSessionViaHttp()

        mockMvc.perform(get("/api/sessions/$sessionId/votes/roster"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `the roster response body contains no token hash, no other-participant vote detail and no session-level completion flag`() {
        val (sessionId, joinCode) = createSessionViaHttp()
        val token = joinViaHttp(joinCode, "Alice")
        val session = sessionRepository.findById(UUID.fromString(sessionId)).get()
        pinDeck(session, listOf(550L))

        val responseBody = mockMvc.perform(
            get("/api/sessions/$sessionId/votes/roster")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        assertFalse(responseBody.contains("tokenHash", ignoreCase = true), "response must never disclose a token hash")
        assertFalse(responseBody.contains("isComplete"), "roster response must not carry a session-level completion flag (P-02)")
        assertFalse(responseBody.contains("likeCounts"), "roster response must not carry per-movie vote detail")
    }
}
