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

private const val SINGLE_MOVIE_DISCOVER_FIXTURE = """
{
  "page": 1,
  "results": [
    {"id": 550, "title": "Fight Club", "poster_path": "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", "genre_ids": [18, 53], "vote_average": 8.4, "popularity": 61.4, "release_date": "1999-10-15", "overview": "An insomniac office worker..."}
  ],
  "total_results": 1,
  "total_pages": 1
}
"""

// Flat reference-catalogue fixture for CatalogReferenceService's provider-id validation domain
// (Plan 03-03), consulted at session-creation/filter-replacement time, not at deck-fetch time.
private const val WATCH_PROVIDER_LIST_FIXTURE = """
{ "results": [ {"provider_id": 8, "provider_name": "Netflix", "logo_path": "/netflix.jpg", "display_priority": 1}, {"provider_id": 337, "provider_name": "Disney Plus", "logo_path": "/disney.jpg", "display_priority": 2} ] }
"""

// Per-movie availability fixture carrying both DE and US entries with different providers and
// links -- reused across every test below. Whichever region MovieCatalogService actually resolves
// against determines which entry a movie's response carries, so this single fixture doubles as
// the cross-region-leak check: if the wrong region's data were ever resolved, these tests would
// see the other region's provider id/link instead of the expected one.
private const val MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE = """
{"id": 0, "results": {
  "DE": {"link": "https://example.com/de", "flatrate": [{"provider_id": 8, "provider_name": "Netflix", "logo_path": "/netflix.jpg", "display_priority": 1}], "rent": [], "buy": [], "ads": []},
  "US": {"link": "https://example.com/us", "flatrate": [{"provider_id": 337, "provider_name": "Disney Plus", "logo_path": "/disney.jpg", "display_priority": 1}], "rent": [], "buy": [], "ads": []}
}}
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
    lateinit var watchProviderRepository: WatchProviderRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearDeckCache() {
        deckCacheRepository.deleteAll()
        // The genre/provider validation lookups are lazy-on-miss just like the deck cache;
        // clearing them here keeps each test's request-count assertions deterministic regardless
        // of test order -- this task's session-creation-with-providers tests would otherwise see
        // a prior test's already-fresh region and silently never consume their enqueued fixture.
        genreRepository.deleteAll()
        watchProviderRepository.deleteAll()
    }

    private fun createSession(body: String? = null): Map<String, Any> {
        val request = post("/api/sessions")
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body)
        }
        val response = mockMvc.perform(request)
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
        // Plan 03-04: the session's region (default DE) is always passed now, so the refresh path
        // resolves per-movie availability for all 5 fixture movies -- one fixture per movie.
        repeat(5) { enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE) }

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
        repeat(5) { takeRecordedRequest() }

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
        repeat(5) { enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE) }

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
        repeat(5) { enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE) }

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
        repeat(5) { enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE) }

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
        repeat(5) { takeRecordedRequest() }
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
        repeat(5) { enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE) }

        mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)

        // requireKnownGenre(null) is a no-op -- no genre-list fetch. The discover call plus one
        // availability call per fixture movie (5) is what actually happened.
        assertEquals(6, requestCount() - before)
        val recorded = takeRecordedRequest()
        assertFalse(recorded.requestLine.contains("/genre/movie/list"), "expected no genre-list request, got ${recorded.requestLine}")
        repeat(5) { takeRecordedRequest() }
    }

    @Test
    fun `a deck request against a session with an empty provider selection carries no provider filter or region parameter, and movies still carry the session's region availability`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String
        val token = joinSession(session["joinCode"] as String, "Ivan")["token"] as String

        enqueueJson(DISCOVER_FIXTURE)
        repeat(5) { enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE) }

        val responseBody = mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val discoverRequest = takeRecordedRequest()
        assertFalse(discoverRequest.requestLine.contains("with_watch_providers"), discoverRequest.requestLine)
        assertFalse(discoverRequest.requestLine.contains("watch_region"), discoverRequest.requestLine)
        repeat(5) { takeRecordedRequest() }

        @Suppress("UNCHECKED_CAST")
        val deck = objectMapper.readValue(responseBody, Map::class.java) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val movies = deck["movies"] as List<Map<String, Any>>
        assertEquals(5, movies.size)
        movies.forEach { movie ->
            @Suppress("UNCHECKED_CAST")
            val providers = movie["providers"] as List<Map<String, Any>>
            assertTrue(providers.isNotEmpty(), "expected the session's DE region availability on every movie")
            assertEquals(8, providers[0]["providerId"])
            assertEquals("https://example.com/de", movie["watchLink"])
        }
    }

    @Test
    fun `a deck request against a session selecting two providers sends both ids in one comma-separated filter with the session's region, and movies carry resolved availability`() {
        enqueueJson(WATCH_PROVIDER_LIST_FIXTURE)
        val session = createSession("""{"region":"DE","providerIds":[8,337]}""")
        val sessionId = session["sessionId"] as String
        val token = joinSession(session["joinCode"] as String, "Judy")["token"] as String
        // Drain the reference-data validation request createSession triggered (Plan 03-03's
        // requireKnownProviders) -- otherwise it sits at the head of the recorded-request queue
        // and the assertion below would inspect it instead of the discover request.
        takeRecordedRequest()

        enqueueJson(DISCOVER_FIXTURE)
        repeat(5) { enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE) }

        val responseBody = mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val discoverRequest = takeRecordedRequest()
        assertTrue(
            discoverRequest.requestLine.contains("with_watch_providers=8,337") ||
                discoverRequest.requestLine.contains("with_watch_providers=8%2C337"),
            "expected a single comma-separated provider filter carrying both ids, got ${discoverRequest.requestLine}",
        )
        assertTrue(discoverRequest.requestLine.contains("watch_region=DE"), discoverRequest.requestLine)
        repeat(5) { takeRecordedRequest() }

        @Suppress("UNCHECKED_CAST")
        val deck = objectMapper.readValue(responseBody, Map::class.java) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val movies = deck["movies"] as List<Map<String, Any>>
        movies.forEach { movie ->
            @Suppress("UNCHECKED_CAST")
            val providers = movie["providers"] as List<Map<String, Any>>
            assertTrue(providers.isNotEmpty())
            assertEquals("https://example.com/de", movie["watchLink"])
        }
    }

    @Test
    fun `the deck response's movies each carry a non-empty provider list and a watch link when the availability fixture lists that region`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String
        val token = joinSession(session["joinCode"] as String, "Peggy")["token"] as String

        enqueueJson(SINGLE_MOVIE_DISCOVER_FIXTURE)
        enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE)

        val responseBody = mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        @Suppress("UNCHECKED_CAST")
        val movie = (objectMapper.readValue(responseBody, Map::class.java)["movies"] as List<Map<String, Any>>)[0]
        @Suppress("UNCHECKED_CAST")
        val providers = movie["providers"] as List<Map<String, Any>>
        assertEquals(1, providers.size, "expected the DE fixture's single provider entry")
        assertEquals("Netflix", providers[0]["providerName"])
        assertEquals("https://example.com/de", movie["watchLink"])
    }

    @Test
    fun `two sessions with the same genre and provider selection but different regions produce two distinct cache rows carrying their own region's availability`() {
        enqueueJson(WATCH_PROVIDER_LIST_FIXTURE)
        val sessionDe = createSession("""{"region":"DE","providerIds":[8]}""")
        enqueueJson(WATCH_PROVIDER_LIST_FIXTURE)
        val sessionUs = createSession("""{"region":"US","providerIds":[8]}""")

        val tokenDe = joinSession(sessionDe["joinCode"] as String, "Karl")["token"] as String
        val tokenUs = joinSession(sessionUs["joinCode"] as String, "Laura")["token"] as String

        enqueueJson(SINGLE_MOVIE_DISCOVER_FIXTURE)
        enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE)
        val bodyDe = mockMvc.perform(
            get("/api/sessions/${sessionDe["sessionId"]}/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $tokenDe")
        ).andExpect(status().isOk).andReturn().response.contentAsString

        enqueueJson(SINGLE_MOVIE_DISCOVER_FIXTURE)
        enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE)
        val bodyUs = mockMvc.perform(
            get("/api/sessions/${sessionUs["sessionId"]}/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $tokenUs")
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val cacheRowCount = jdbcTemplate.queryForObject("SELECT count(*) FROM deck_cache_entry", Int::class.java)
        assertEquals(2, cacheRowCount)

        @Suppress("UNCHECKED_CAST")
        val movieDe = (objectMapper.readValue(bodyDe, Map::class.java)["movies"] as List<Map<String, Any>>)[0]
        @Suppress("UNCHECKED_CAST")
        val movieUs = (objectMapper.readValue(bodyUs, Map::class.java)["movies"] as List<Map<String, Any>>)[0]

        @Suppress("UNCHECKED_CAST")
        val providersDe = movieDe["providers"] as List<Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val providersUs = movieUs["providers"] as List<Map<String, Any>>
        assertEquals(8, providersDe[0]["providerId"], "DE session must carry DE's own resolved provider, never US's")
        assertEquals("https://example.com/de", movieDe["watchLink"])
        assertEquals(337, providersUs[0]["providerId"], "US session must carry US's own resolved provider, never DE's")
        assertEquals("https://example.com/us", movieUs["watchLink"])
    }

    @Test
    fun `the deck endpoint ignores a client-supplied region or provider query parameter -- the outbound query matches a request without them`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String
        val token = joinSession(session["joinCode"] as String, "Mallory")["token"] as String

        enqueueJson(DISCOVER_FIXTURE)
        repeat(5) { enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE) }

        mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .param("region", "US")
                .param("provider", "999")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)

        val discoverRequest = takeRecordedRequest()
        assertFalse(discoverRequest.requestLine.contains("with_watch_providers"), discoverRequest.requestLine)
        assertFalse(discoverRequest.requestLine.contains("watch_region"), discoverRequest.requestLine)
        repeat(5) { takeRecordedRequest() }
    }

    @Test
    fun `a stale cache row served after an upstream outage surfaces as a 200 with stale true`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String
        val token = joinSession(session["joinCode"] as String, "Quentin")["token"] as String

        enqueueJson(SINGLE_MOVIE_DISCOVER_FIXTURE)
        enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE)
        mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        ).andExpect(status().isOk)

        val cacheKey = buildDeckCacheKey(null, emptyList(), "DE")
        jdbcTemplate.update(
            "UPDATE deck_cache_entry SET fetched_at = ? WHERE cache_key = ?",
            java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofHours(7))),
            cacheKey,
        )

        // RETRY_MAX_ATTEMPTS=3 -> 1 initial attempt + 3 retries = 4 total attempts before exhaustion.
        repeat(4) { enqueueJson(SINGLE_MOVIE_DISCOVER_FIXTURE, status = 500) }

        val responseBody = mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        @Suppress("UNCHECKED_CAST")
        val deck = objectMapper.readValue(responseBody, Map::class.java) as Map<String, Any>
        assertTrue(deck["stale"] as Boolean, "expected the outage fallback to be marked stale")
        @Suppress("UNCHECKED_CAST")
        val movies = deck["movies"] as List<Map<String, Any>>
        assertEquals(1, movies.size)
    }

    @Test
    fun `an upstream outage with no cached deck for these filters surfaces as a 503`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String
        val token = joinSession(session["joinCode"] as String, "Romeo")["token"] as String

        repeat(4) { enqueueJson(SINGLE_MOVIE_DISCOVER_FIXTURE, status = 500) }

        mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        ).andExpect(status().isServiceUnavailable)
    }

    @Test
    fun `two different participants of the same session receive identical deck filtering`() {
        enqueueJson(WATCH_PROVIDER_LIST_FIXTURE)
        val session = createSession("""{"region":"DE","providerIds":[8]}""")
        val sessionId = session["sessionId"] as String
        val joinCode = session["joinCode"] as String
        val aliceToken = joinSession(joinCode, "Nadia")["token"] as String
        val bobToken = joinSession(joinCode, "Oscar")["token"] as String

        enqueueJson(SINGLE_MOVIE_DISCOVER_FIXTURE)
        enqueueJson(MULTI_REGION_MOVIE_AVAILABILITY_FIXTURE)

        val aliceBody = mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $aliceToken")
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val before = requestCount()
        val bobBody = mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $bobToken")
        ).andExpect(status().isOk).andReturn().response.contentAsString

        // The second participant's read is a cache hit for the same session-driven filter combo
        // -- zero further upstream calls -- and, timestamp field aside, identical deck content.
        assertEquals(0, requestCount() - before)
        @Suppress("UNCHECKED_CAST")
        val aliceDeck = objectMapper.readValue(aliceBody, Map::class.java) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val bobDeck = objectMapper.readValue(bobBody, Map::class.java) as Map<String, Any>
        assertEquals(aliceDeck["movies"], bobDeck["movies"])
        assertEquals(aliceDeck["totalResults"], bobDeck["totalResults"])
    }
}
