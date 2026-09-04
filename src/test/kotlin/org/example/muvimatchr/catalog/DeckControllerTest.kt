package org.example.muvimatchr.catalog

import org.example.muvimatchr.support.FAKE_TMDB_TOKEN
import org.example.muvimatchr.support.TmdbMockServerSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

private const val GENRE_FIXTURE = """
{ "genres": [ {"id": 28, "name": "Action"}, {"id": 18, "name": "Drama"} ] }
"""

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

@AutoConfigureMockMvc
class DeckControllerTest : TmdbMockServerSupport() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var deckCacheRepository: DeckCacheRepository

    @Autowired
    lateinit var genreRepository: GenreRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearDeckCache() {
        deckCacheRepository.deleteAll()
        // The genre validation lookup is lazy-on-miss just like the deck cache; clearing it here
        // keeps each test's request-count assertions deterministic regardless of test order.
        genreRepository.deleteAll()
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

    @Test
    fun `GET deck with a genre filter returns real upstream movie data translated into the app's own DTOs`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val joinResponse = joinSession(joinCode, "Alice")
        val token = joinResponse["token"] as String

        // requireKnownGenre runs before the discover call, so the genre-list fixture (populating
        // the validation domain with id 28) must be enqueued first.
        enqueueJson(GENRE_FIXTURE)
        enqueueJson(DISCOVER_FIXTURE)

        val responseBody = mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .param("genre", "28")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        @Suppress("UNCHECKED_CAST")
        val deck = objectMapper.readValue(responseBody, Map::class.java) as Map<String, Any>
        assertEquals("ok", deck["status"])
        assertFalse(deck["stale"] as Boolean)

        @Suppress("UNCHECKED_CAST")
        val movies = deck["movies"] as List<Map<String, Any>>
        assertEquals(5, movies.size)

        val first = movies[0]
        assertEquals("Fight Club", first["title"])
        assertEquals("/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", first["posterPath"])
        assertEquals(listOf(18, 53), first["genreIds"])
        assertEquals(8.4, first["voteAverage"])

        val genreRequest = takeRecordedRequest()
        assertTrue(genreRequest.requestLine.contains("/genre/movie/list"), "expected the genre-list validation request first, got ${genreRequest.requestLine}")
        val recorded = takeRecordedRequest()
        val requestLine = recorded.requestLine
        assertTrue(requestLine.contains("with_genres=28"), "expected with_genres=28 in $requestLine")
        assertTrue(requestLine.contains("sort_by=popularity.desc"), "expected sort_by=popularity.desc in $requestLine")

        val cacheRowCount = jdbcTemplate.queryForObject("SELECT count(*) FROM deck_cache_entry", Int::class.java)
        assertEquals(1, cacheRowCount)
    }

    @Test
    fun `GET deck response body never contains the TMDB credential value`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val joinResponse = joinSession(joinCode, "Bob")
        val token = joinResponse["token"] as String

        enqueueJson(DISCOVER_FIXTURE)

        val responseBody = mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        assertFalse(responseBody.contains(FAKE_TMDB_TOKEN), "TMDB credential leaked into the deck response body")
    }

    @Test
    fun `GET deck response headers never contain the TMDB credential value`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val joinResponse = joinSession(joinCode, "Carol")
        val token = joinResponse["token"] as String

        enqueueJson(DISCOVER_FIXTURE)

        val response = mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response

        for (headerName in response.headerNames) {
            for (headerValue in response.getHeaders(headerName)) {
                assertFalse(
                    headerValue.contains(FAKE_TMDB_TOKEN),
                    "TMDB credential leaked into response header '$headerName'",
                )
            }
        }
    }

    @Test
    fun `GET deck with a genre id present in the cached genre list succeeds and validates before the discover call`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val token = joinSession(joinCode, "Frank")["token"] as String

        enqueueJson(GENRE_FIXTURE)
        enqueueJson(DISCOVER_FIXTURE)

        mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .param("genre", "28")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)

        val genreRequest = takeRecordedRequest()
        assertTrue(genreRequest.requestLine.contains("/genre/movie/list"), "expected the genre-list request first, got ${genreRequest.requestLine}")
        val discoverRequest = takeRecordedRequest()
        assertTrue(discoverRequest.requestLine.contains("with_genres=28"), "expected the discover request second, got ${discoverRequest.requestLine}")
    }

    @Test
    fun `GET deck with an unknown genre id returns 400 and triggers no additional discover call`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val token = joinSession(joinCode, "Grace")["token"] as String

        // Only the genre-list fixture is enqueued -- if the code incorrectly proceeded to a
        // discover call anyway, MockWebServer would have nothing matching to serve and the test
        // would fail loudly rather than silently passing.
        enqueueJson(GENRE_FIXTURE)
        val before = requestCount()

        mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .param("genre", "9999")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isBadRequest)

        assertEquals(1, requestCount() - before, "expected exactly one request (the genre-list validation fetch), no discover call")
        val recorded = takeRecordedRequest()
        assertTrue(recorded.requestLine.contains("/genre/movie/list"), "expected only the genre-list request, got ${recorded.requestLine}")
    }

    @Test
    fun `GET deck with no genre at all succeeds without performing any genre validation lookup`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val token = joinSession(joinCode, "Heidi")["token"] as String

        val before = requestCount()
        enqueueJson(DISCOVER_FIXTURE)

        mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)

        // requireKnownGenre(null) is a no-op -- only the discover call happened, no genre-list fetch.
        assertEquals(1, requestCount() - before)
        val recorded = takeRecordedRequest()
        assertFalse(recorded.requestLine.contains("/genre/movie/list"), "expected no genre-list request, got ${recorded.requestLine}")
    }
}
