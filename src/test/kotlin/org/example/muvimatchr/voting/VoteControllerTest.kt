package org.example.muvimatchr.voting

import org.example.muvimatchr.support.TmdbMockServerSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

@AutoConfigureMockMvc
class VoteControllerTest : TmdbMockServerSupport() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

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
    private fun createSessionWithPinnedDeck(displayName: String = "Alice"): Triple<String, String, List<Long>> {
        val session = createSession()
        val sessionId = session["sessionId"] as String
        val joinCode = session["joinCode"] as String
        val token = joinSession(joinCode, displayName)["token"] as String

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
}
