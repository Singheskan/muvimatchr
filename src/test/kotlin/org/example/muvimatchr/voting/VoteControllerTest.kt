package org.example.muvimatchr.voting

import org.example.muvimatchr.catalog.DeckCacheRepository
import org.example.muvimatchr.support.TmdbMockServerSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

// Mirrors DeckControllerTest's DISCOVER_FIXTURE exactly: five movies, above MINIMUM_DECK_SIZE, so
// a deck GET against these fixtures pins on the "ok" branch.
private const val DISCOVER_FIXTURE = """
{
  "page": 1,
  "results": [
    {"id": 550, "title": "Fight Club", "poster_path": "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", "genre_ids": [18, 53], "vote_average": 8.4, "popularity": 61.4, "release_date": "1999-10-15", "overview": "An insomniac office worker..."},
    {"id": 155, "title": "The Dark Knight", "poster_path": "/qJ2tW6WMUDux911r6m7haRef0WH.jpg", "genre_ids": [18, 28, 80], "vote_average": 8.5, "popularity": 90.1, "release_date": "2008-07-16", "overview": "Batman raises the stakes..."},
    {"id": 27205, "title": "Inception", "poster_path": "/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg", "genre_ids": [28, 878, 12], "vote_average": 8.3, "popularity": 75.2, "release_date": "2010-07-15", "overview": "Cobb steals corporate secrets..."},
    {"id": 424, "title": "Schindler's List", "poster_path": "/sF1U4EUQS8YHUYjNl3pMGNIQyr0.jpg", "genre_ids": [18, 36, 10752], "vote_average": 8.6, "popularity": 45.3, "release_date": "1993-12-15", "overview": "In Poland during World War II..."},
    {"id": 680, "title": "Pulp Fiction", "poster_path": "/d5iIlFn5s0ImszYzBPb8JPIfbXD.jpg", "genre_ids": [53, 80], "vote_average": 8.5, "popularity": 55.7, "release_date": "1994-09-10", "overview": "A burger-loving hit man..."}
  ],
  "total_results": 5,
  "total_pages": 1
}
"""

private const val MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE = """
{"id": 0, "results": {
  "DE": {"link": "https://example.com/de", "flatrate": [{"provider_id": 8, "provider_name": "Netflix", "logo_path": "/netflix.jpg", "display_priority": 1}], "rent": [], "buy": [], "ads": []},
  "US": {"link": "https://example.com/us", "flatrate": [{"provider_id": 337, "provider_name": "Disney Plus", "logo_path": "/disney.jpg", "display_priority": 1}], "rent": [], "buy": [], "ads": []}
}}
"""

// Mirrors DeckControllerTest's D-06 boundary fixture: exactly four movies -- one below
// MINIMUM_DECK_SIZE -- so a deck GET against this fixture resolves insufficient_results and never
// pins the session.
private const val FOUR_MOVIE_DISCOVER_FIXTURE = """
{
  "page": 1,
  "results": [
    {"id": 1, "title": "Movie One", "poster_path": "/one.jpg", "genre_ids": [28], "vote_average": 7.0, "popularity": 10.0, "release_date": "2020-01-01", "overview": "..."},
    {"id": 2, "title": "Movie Two", "poster_path": "/two.jpg", "genre_ids": [28], "vote_average": 7.1, "popularity": 10.1, "release_date": "2020-01-02", "overview": "..."},
    {"id": 3, "title": "Movie Three", "poster_path": "/three.jpg", "genre_ids": [28], "vote_average": 7.2, "popularity": 10.2, "release_date": "2020-01-03", "overview": "..."},
    {"id": 4, "title": "Movie Four", "poster_path": "/four.jpg", "genre_ids": [28], "vote_average": 7.3, "popularity": 10.3, "release_date": "2020-01-04", "overview": "..."}
  ],
  "total_results": 4,
  "total_pages": 1
}
"""

@AutoConfigureMockMvc
class VoteControllerTest : TmdbMockServerSupport() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var deckCacheRepository: DeckCacheRepository

    // Deck caching (MovieCatalogService) is keyed by filter combination (genre, providerIds,
    // region), not by session -- shared across every session and every test in this class within
    // the deck TTL. Without clearing it, a later test's below-minimum fixture (enqueued but never
    // consumed) would silently be skipped in favor of an earlier test's already-cached, larger
    // deck for the same (null, [], DE) key, same reasoning as DeckControllerTest's own
    // clearDeckCache.
    @BeforeEach
    fun clearDeckCache() {
        deckCacheRepository.deleteAll()
    }

    private fun createSession(): Map<String, Any> {
        val response = mockMvc.perform(post("/api/sessions"))
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString
        @Suppress("UNCHECKED_CAST")
        return objectMapper.readValue(response, Map::class.java) as Map<String, Any>
    }

    private fun joinSession(joinCode: String, displayName: String): Map<String, Any> {
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
        return objectMapper.readValue(response, Map::class.java) as Map<String, Any>
    }

    // Creates a session, joins one participant, enqueues the discover + availability fixtures,
    // performs the deck GET (which pins it per D-04), and returns the session id, participant
    // token and the pinned movie ids -- the shared setup every vote test in this class builds on.
    //
    // Deck caching (MovieCatalogService) is keyed by filter combination (genre, providerIds,
    // region), not by session -- every session created with default filters resolves the same
    // cache key. Without clearing the cache immediately before this helper's own deck GET, a
    // second call to this helper within the same test (e.g. a cross-session test creating two
    // pinned sessions) would hit the first call's now-cached row instead of performing a fresh
    // fetch, silently leaving this call's own enqueued fixtures unconsumed -- to leak into
    // whichever test runs next.
    private fun createSessionWithPinnedDeck(displayName: String = "Alice"): Triple<String, String, List<Long>> {
        val session = createSession()
        val sessionId = session["sessionId"] as String
        val joinCode = session["joinCode"] as String
        val token = joinSession(joinCode, displayName)["token"] as String

        deckCacheRepository.deleteAll()
        enqueueJson(DISCOVER_FIXTURE)
        repeat(5) { enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE) }

        val deckBody = mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        @Suppress("UNCHECKED_CAST")
        val deck = objectMapper.readValue(deckBody, Map::class.java) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val movies = deck["movies"] as List<Map<String, Any>>
        val movieIds = movies.map { (it["tmdbId"] as Number).toLong() }

        return Triple(sessionId, token, movieIds)
    }

    private fun voteRowCount(sessionId: String, movieId: Long): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vote WHERE session_id = CAST(? AS uuid) AND movie_id = ?",
            Int::class.java,
            sessionId,
            movieId,
        )!!

    private fun voteRowCountForSession(sessionId: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vote WHERE session_id = CAST(? AS uuid)",
            Int::class.java,
            sessionId,
        )!!

    private fun voteChoice(sessionId: String, movieId: Long): String =
        jdbcTemplate.queryForObject(
            "SELECT choice FROM vote WHERE session_id = CAST(? AS uuid) AND movie_id = ?",
            String::class.java,
            sessionId,
            movieId,
        )!!

    private fun deckPinnedAt(sessionId: String): java.sql.Timestamp? =
        jdbcTemplate.queryForObject(
            "SELECT deck_pinned_at FROM session WHERE id = CAST(? AS uuid)",
            java.sql.Timestamp::class.java,
            sessionId,
        )

    @Test
    fun `POST a like for a pinned movie persists exactly one vote row and returns a live status`() {
        val (sessionId, token, movieIds) = createSessionWithPinnedDeck()
        val movieId = movieIds.first()

        val responseBody = mockMvc.perform(
            post("/api/sessions/$sessionId/votes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"movieId":$movieId,"choice":"LIKE"}""")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        @Suppress("UNCHECKED_CAST")
        val status = objectMapper.readValue(responseBody, Map::class.java) as Map<String, Any>
        assertEquals(5, status["deckSize"])
        assertEquals(0, status["finishedCount"])
        assertFalse(status["isComplete"] as Boolean)

        val voteRowCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vote WHERE session_id = CAST(? AS uuid) AND movie_id = ?",
            Int::class.java,
            sessionId,
            movieId,
        )
        assertEquals(1, voteRowCount, "expected exactly one vote row for this participant/movie immediately after the call returns")
    }

    @Test
    fun `a second deck GET for a pinned session returns the identical movie list and makes zero further upstream requests`() {
        val (sessionId, token, firstMovieIds) = createSessionWithPinnedDeck("Bob")

        val before = requestCount()
        val secondDeckBody = mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        assertEquals(0, requestCount() - before, "the pinned snapshot must be served without any further upstream catalog request")

        @Suppress("UNCHECKED_CAST")
        val secondDeck = objectMapper.readValue(secondDeckBody, Map::class.java) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val secondMovies = secondDeck["movies"] as List<Map<String, Any>>
        val secondMovieIds = secondMovies.map { (it["tmdbId"] as Number).toLong() }
        assertEquals(firstMovieIds, secondMovieIds, "the second deck read must return the exact same pinned movie list")
    }

    @Test
    fun `a repeat POST with a different choice updates the existing vote row in place instead of duplicating`() {
        val (sessionId, token, movieIds) = createSessionWithPinnedDeck("Carol")
        val movieId = movieIds.first()

        mockMvc.perform(
            post("/api/sessions/$sessionId/votes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"movieId":$movieId,"choice":"LIKE"}""")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/sessions/$sessionId/votes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"movieId":$movieId,"choice":"PASS"}""")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        ).andExpect(status().isOk)

        assertEquals(1, voteRowCount(sessionId, movieId), "a differing repeat choice must update in place, not duplicate")
        assertEquals("PASS", voteChoice(sessionId, movieId))
    }

    @Test
    fun `a repeat POST with the identical choice leaves exactly one vote row`() {
        val (sessionId, token, movieIds) = createSessionWithPinnedDeck("Dave")
        val movieId = movieIds.first()

        repeat(2) {
            mockMvc.perform(
                post("/api/sessions/$sessionId/votes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"movieId":$movieId,"choice":"LIKE"}""")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            ).andExpect(status().isOk)
        }

        assertEquals(1, voteRowCount(sessionId, movieId), "an identical repeat submission (e.g. a network retry) must not duplicate the row")
    }

    @Test
    fun `POST an off-deck movieId is rejected with 400 and writes no vote row`() {
        val (sessionId, token, movieIds) = createSessionWithPinnedDeck("Erin")
        val offDeckMovieId = (movieIds.maxOrNull() ?: 0L) + 999_999L

        mockMvc.perform(
            post("/api/sessions/$sessionId/votes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"movieId":$offDeckMovieId,"choice":"LIKE"}""")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        ).andExpect(status().isBadRequest)

        assertEquals(0, voteRowCount(sessionId, offDeckMovieId))
    }

    @Test
    fun `POST with a bearer token issued for a different session is rejected with 404 and writes no vote row in either session`() {
        val (sessionIdA, tokenA, _) = createSessionWithPinnedDeck("Frank")
        val (sessionIdB, _, movieIdsB) = createSessionWithPinnedDeck("Grace")

        // Session A's own token, used against session B's votes endpoint.
        val movieId = movieIdsB.first()
        mockMvc.perform(
            post("/api/sessions/$sessionIdB/votes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"movieId":$movieId,"choice":"LIKE"}""")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $tokenA")
        ).andExpect(status().isNotFound)

        assertEquals(0, voteRowCountForSession(sessionIdA))
        assertEquals(0, voteRowCountForSession(sessionIdB))
    }

    @Test
    fun `POST against a session whose deck has never been pinned is rejected with 409 and writes no vote row`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String
        val token = joinSession(session["joinCode"] as String, "Heidi")["token"] as String

        mockMvc.perform(
            post("/api/sessions/$sessionId/votes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"movieId":550,"choice":"LIKE"}""")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        ).andExpect(status().isConflict)

        assertEquals(0, voteRowCountForSession(sessionId))
    }

    @Test
    fun `votes status for a pinned session with no votes reports zero finished and not complete`() {
        val (sessionId, token, _) = createSessionWithPinnedDeck("Ivan")

        val responseBody = mockMvc.perform(
            get("/api/sessions/$sessionId/votes/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        @Suppress("UNCHECKED_CAST")
        val body = objectMapper.readValue(responseBody, Map::class.java) as Map<String, Any>
        assertEquals(0, body["finishedCount"])
        assertFalse(body["isComplete"] as Boolean)
    }

    @Test
    fun `votes status for a session whose deck was never pinned reports zero deck size and not complete`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String
        val token = joinSession(session["joinCode"] as String, "Judy")["token"] as String

        val responseBody = mockMvc.perform(
            get("/api/sessions/$sessionId/votes/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        @Suppress("UNCHECKED_CAST")
        val body = objectMapper.readValue(responseBody, Map::class.java) as Map<String, Any>
        assertEquals(0, body["deckSize"])
        assertFalse(body["isComplete"] as Boolean)
    }

    @Test
    fun `a deck resolving below the catalog minimum leaves deck_pinned_at null and a subsequent vote is rejected with 409`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String
        val token = joinSession(session["joinCode"] as String, "Karl")["token"] as String

        enqueueJson(FOUR_MOVIE_DISCOVER_FIXTURE)
        repeat(4) { enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE) }

        mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        ).andExpect(status().isOk)

        assertNull(deckPinnedAt(sessionId), "a below-minimum deck must never be pinned")

        mockMvc.perform(
            post("/api/sessions/$sessionId/votes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"movieId":1,"choice":"LIKE"}""")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        ).andExpect(status().isConflict)
    }
}
