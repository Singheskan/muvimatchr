package org.example.muvimatchr.session

import org.example.muvimatchr.catalog.GenreRepository
import org.example.muvimatchr.catalog.WatchProviderRepository
import org.example.muvimatchr.support.TmdbMockServerSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

// Session-creation-time genre validation fixture (mirrors DeckControllerTest's own GENRE_FIXTURE,
// which cannot be reused across files -- Kotlin top-level `private const val` is file-private).
private const val GENRE_FIXTURE = """
{ "genres": [ {"id": 28, "name": "Action"}, {"id": 18, "name": "Drama"} ] }
"""

// A five-movie discover response -- at or above DeckController's MINIMUM_DECK_SIZE floor, so a
// deck GET against it resolves "ok" and pins the session (D-04). Content mirrors
// DeckControllerTest's own DISCOVER_FIXTURE; only the count (five) and pinning outcome matter
// here, not any per-movie field.
private const val DISCOVER_FIXTURE = """
{
  "page": 1,
  "results": [
    {"id": 550, "title": "Fight Club", "poster_path": "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", "genre_ids": [18, 53], "vote_average": 8.4, "popularity": 61.4, "release_date": "1999-10-15", "overview": "..."},
    {"id": 155, "title": "The Dark Knight", "poster_path": "/qJ2tW6WMUDux911r6m7haRef0WH.jpg", "genre_ids": [18, 28, 80], "vote_average": 8.5, "popularity": 90.1, "release_date": "2008-07-16", "overview": "..."},
    {"id": 27205, "title": "Inception", "poster_path": "/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg", "genre_ids": [28, 878, 12], "vote_average": 8.3, "popularity": 75.2, "release_date": "2010-07-15", "overview": "..."},
    {"id": 424, "title": "Schindler's List", "poster_path": "/sF1U4EUQS8YHUYjNl3pMGNIQyr0.jpg", "genre_ids": [18, 36, 10752], "vote_average": 8.6, "popularity": 45.3, "release_date": "1993-12-15", "overview": "..."},
    {"id": 680, "title": "Pulp Fiction", "poster_path": "/d5iIlFn5s0ImszYzBPb8JPIfbXD.jpg", "genre_ids": [53, 80], "vote_average": 8.5, "popularity": 55.7, "release_date": "1994-09-10", "overview": "..."}
  ],
  "total_results": 5,
  "total_pages": 1
}
"""

// Per-movie availability fixture, DE-only -- sufficient for a pin to succeed; the region-specific
// resolution itself is DeckControllerTest's concern, not this file's.
private const val MOVIE_AVAILABILITY_FIXTURE = """
{"id": 0, "results": {
  "DE": {"link": "https://example.com/de", "flatrate": [{"provider_id": 8, "provider_name": "Netflix", "logo_path": "/netflix.jpg", "display_priority": 1}], "rent": [], "buy": [], "ads": []}
}}
"""

// Extends TmdbMockServerSupport (not plain PostgresTestSupport): SessionController now validates
// every non-empty provider-id selection against CatalogReferenceService, which lazily fetches the
// region's watch-provider list from TMDB on first use -- these tests fake that call rather than
// hitting real TMDB.
@AutoConfigureMockMvc
class SessionFiltersTest : TmdbMockServerSupport() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var sessionRepository: SessionRepository

    @Autowired
    lateinit var watchProviderRepository: WatchProviderRepository

    @Autowired
    lateinit var genreRepository: GenreRepository

    // Lazy-on-miss like the deck cache and provider validation domain; clearing before every test
    // keeps the "which region/genre scope has already been fetched" state deterministic across
    // test order.
    @BeforeEach
    fun clearReferenceData() {
        watchProviderRepository.deleteAll()
        genreRepository.deleteAll()
    }

    private fun providersFixture(vararg providers: Pair<Int, String>): String {
        val results = providers.joinToString(",") { (id, name) ->
            """{"provider_id": $id, "provider_name": "$name", "logo_path": "/logo$id.jpg", "display_priority": $id}"""
        }
        return """{ "results": [ $results ] }"""
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

    // Pins a session's deck by enqueueing a five-movie discover fixture (at DeckController's
    // MINIMUM_DECK_SIZE floor) plus one availability response per movie, then performing a single
    // authenticated deck GET -- the first successful deck read is what triggers pinning (D-04).
    // Asserts the pin actually took before returning, so a caller can rely on deckPinnedAt being
    // non-null immediately afterward.
    private fun pinDeck(sessionId: String, token: String) {
        enqueueJson(DISCOVER_FIXTURE)
        repeat(5) { enqueueJson(MOVIE_AVAILABILITY_FIXTURE) }
        mockMvc.perform(
            get("/api/sessions/$sessionId/deck")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        ).andExpect(status().isOk)

        val pinned = sessionRepository.findById(UUID.fromString(sessionId)).get()
        assertTrue(pinned.deckPinnedAt != null, "expected the deck GET above to have pinned the session")
    }

    @Test
    fun `creating a session with no request body returns 201 with region DE and empty provider ids`() {
        val session = createSession()

        assertEquals(DEFAULT_REGION, session["region"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyList<Int>(), session["providerIds"] as List<Int>)
    }

    @Test
    fun `creating a session with a body specifying region and providers echoes exactly that region and those ids`() {
        enqueueJson(providersFixture(8 to "Netflix", 337 to "Disney Plus"))

        val session = createSession("""{"region":"US","providerIds":[8,337]}""")

        assertEquals("US", session["region"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(8, 337), session["providerIds"] as List<Int>)
    }

    @Test
    fun `GET filters with a valid participant token returns the session's current region and provider ids`() {
        enqueueJson(providersFixture(8 to "Netflix"))
        val session = createSession("""{"region":"US","providerIds":[8]}""")
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val joinResponse = joinSession(joinCode, "Alice")
        val token = joinResponse["token"] as String

        mockMvc.perform(
            get("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.region").value("US"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.providerIds[0]").value(8))
    }

    @Test
    fun `PUT filters from a non-creating participant replaces both values and a different participant observes the change`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val first = joinSession(joinCode, "Alice")
        val second = joinSession(joinCode, "Bob")
        val firstToken = first["token"] as String
        val secondToken = second["token"] as String

        enqueueJson(providersFixture(9 to "Amazon Prime Video", 10 to "Apple TV"))

        mockMvc.perform(
            put("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $secondToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"region":"GB","providerIds":[9,10]}""")
        )
            .andExpect(status().isOk)

        val getResponse = mockMvc.perform(
            get("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $firstToken")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        @Suppress("UNCHECKED_CAST")
        val body = objectMapper.readValue(getResponse, Map::class.java) as Map<String, Any>
        assertEquals("GB", body["region"])
        assertEquals(listOf(9, 10), body["providerIds"])
    }

    @Test
    fun `PUT with an empty provider id list clears the selection and a null region resets to DE`() {
        enqueueJson(providersFixture(8 to "Netflix", 9 to "Amazon Prime Video"))
        val session = createSession("""{"region":"US","providerIds":[8,9]}""")
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val token = joinSession(joinCode, "Alice")["token"] as String

        // No provider fixture enqueued here on purpose: an empty providerIds list must clear the
        // selection without consulting the provider catalogue at all -- if the code incorrectly
        // tried to validate anyway, MockWebServer would have nothing queued to serve and the
        // request would fail loudly instead of silently passing.
        mockMvc.perform(
            put("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"providerIds":[]}""")
        )
            .andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.region").value(DEFAULT_REGION))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.providerIds.length()").value(0))
    }

    @Test
    fun `PUT with no Authorization header returns 401`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String

        mockMvc.perform(
            put("/api/sessions/$sessionId/filters")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"region":"US","providerIds":[]}""")
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET with no Authorization header returns 401`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String

        mockMvc.perform(get("/api/sessions/$sessionId/filters"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `PUT using a valid token belonging to a different session returns 404 and target session's filters are unchanged`() {
        enqueueJson(providersFixture(8 to "Netflix"))
        val firstSession = createSession("""{"region":"US","providerIds":[8]}""")
        val firstJoinCode = firstSession["joinCode"] as String
        val firstSessionId = firstSession["sessionId"] as String
        joinSession(firstJoinCode, "Alice")

        val secondSession = createSession()
        val secondJoinCode = secondSession["joinCode"] as String
        val secondToken = joinSession(secondJoinCode, "Bob")["token"] as String

        // Session-membership is checked before any provider validation, so a mismatched-session
        // 404 is returned before requireKnownProviders would ever run -- no provider fixture
        // needed for this PUT itself.
        mockMvc.perform(
            put("/api/sessions/$firstSessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $secondToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"region":"GB","providerIds":[999]}""")
        )
            .andExpect(status().isNotFound)

        val reloaded = sessionRepository.findById(UUID.fromString(firstSessionId)).get()
        assertEquals("US", reloaded.region)
        assertEquals(listOf(8), reloaded.providerIds)
    }

    @Test
    fun `PUT with region Germany returns 400 and stored filters are unchanged`() {
        enqueueJson(providersFixture(8 to "Netflix"))
        val session = createSession("""{"region":"US","providerIds":[8]}""")
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val token = joinSession(joinCode, "Alice")["token"] as String

        // Bean Validation rejects the malformed region before the handler body runs, so no
        // provider fixture is needed for this PUT itself.
        mockMvc.perform(
            put("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"region":"Germany","providerIds":[8]}""")
        )
            .andExpect(status().isBadRequest)

        val reloaded = sessionRepository.findById(UUID.fromString(sessionId)).get()
        assertEquals("US", reloaded.region)
        assertEquals(listOf(8), reloaded.providerIds)
    }

    @Test
    fun `PUT with a provider id of 0 or negative returns 400 and stored filters are unchanged`() {
        enqueueJson(providersFixture(8 to "Netflix"))
        val session = createSession("""{"region":"US","providerIds":[8]}""")
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val token = joinSession(joinCode, "Alice")["token"] as String

        // validateProviderIds rejects a non-positive id before requireKnownProviders would ever
        // run, so no provider fixture is needed for either PUT below.
        mockMvc.perform(
            put("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"region":"US","providerIds":[0]}""")
        )
            .andExpect(status().isBadRequest)

        mockMvc.perform(
            put("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"region":"US","providerIds":[-1]}""")
        )
            .andExpect(status().isBadRequest)

        val reloaded = sessionRepository.findById(UUID.fromString(sessionId)).get()
        assertEquals("US", reloaded.region)
        assertEquals(listOf(8), reloaded.providerIds)
    }

    @Test
    fun `PUT filters with provider ids that all exist for the session's region succeeds and stores them`() {
        // Both providers are cached in the single fetch triggered at session creation -- the PUT
        // below reuses that still-fresh US catalogue and makes no further TMDB call.
        enqueueJson(providersFixture(8 to "Netflix", 337 to "Disney Plus"))
        val session = createSession("""{"region":"US","providerIds":[8]}""")
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val token = joinSession(joinCode, "Ivan")["token"] as String

        mockMvc.perform(
            put("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"region":"US","providerIds":[8,337]}""")
        )
            .andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.providerIds").value(org.hamcrest.Matchers.contains(8, 337)))

        val reloaded = sessionRepository.findById(UUID.fromString(sessionId)).get()
        assertEquals(listOf(8, 337), reloaded.providerIds)
    }

    @Test
    fun `PUT filters naming a provider id absent from the region's cached provider list returns 400 and leaves the stored selection unchanged`() {
        enqueueJson(providersFixture(8 to "Netflix"))
        val session = createSession("""{"region":"US","providerIds":[8]}""")
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val token = joinSession(joinCode, "Judy")["token"] as String

        // The US catalogue only ever contained provider 8 -- 12345 is not in it.
        mockMvc.perform(
            put("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"region":"US","providerIds":[12345]}""")
        )
            .andExpect(status().isBadRequest)

        val reloaded = sessionRepository.findById(UUID.fromString(sessionId)).get()
        assertEquals("US", reloaded.region)
        assertEquals(listOf(8), reloaded.providerIds)
    }

    @Test
    fun `PUT clearing the provider selection with an empty list succeeds without consulting the provider catalogue at all`() {
        enqueueJson(providersFixture(8 to "Netflix"))
        val session = createSession("""{"region":"US","providerIds":[8]}""")
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val token = joinSession(joinCode, "Karl")["token"] as String

        val before = requestCount()

        // No provider fixture enqueued for this call: an empty list must not trigger any
        // requireKnownProviders lookup at all.
        mockMvc.perform(
            put("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"region":"US","providerIds":[]}""")
        )
            .andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.providerIds.length()").value(0))

        assertEquals(0, requestCount() - before)
    }

    @Test
    fun `filters are freely replaceable by any participant before the deck is pinned and refused together with a 409 once it is, leaving the stored configuration unchanged`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String
        val joinCode = session["joinCode"] as String
        val aliceToken = joinSession(joinCode, "Alice")["token"] as String
        val bobToken = joinSession(joinCode, "Bob")["token"] as String

        // Pre-pin: Bob (not the participant who will trigger the pin below) replaces all three
        // filters -- the lock that follows is keyed on pin state, not on which participant is
        // acting (D-02, carrying forward Phase 2's no-host-role decision).
        enqueueJson(providersFixture(8 to "Netflix"))
        enqueueJson(GENRE_FIXTURE)
        mockMvc.perform(
            put("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $bobToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"region":"US","providerIds":[8],"genre":28}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.region").value("US"))
            .andExpect(jsonPath("$.providerIds[0]").value(8))
            .andExpect(jsonPath("$.genre").value(28))

        val beforePin = sessionRepository.findById(UUID.fromString(sessionId)).get()
        assertEquals("US", beforePin.region)
        assertEquals(listOf(8), beforePin.providerIds)
        assertEquals(28, beforePin.genre)

        // Alice's deck read pins the session (D-04).
        pinDeck(sessionId, aliceToken)

        // Post-pin: replacement is refused with 409, and the stored configuration is untouched.
        mockMvc.perform(
            put("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $bobToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"region":"GB","providerIds":[],"genre":18}""")
        )
            .andExpect(status().isConflict)

        val afterRefusal = sessionRepository.findById(UUID.fromString(sessionId)).get()
        assertEquals("US", afterRefusal.region)
        assertEquals(listOf(8), afterRefusal.providerIds)
        assertEquals(28, afterRefusal.genre)

        // Reading configuration is never blocked, only replacing it.
        mockMvc.perform(
            get("/api/sessions/$sessionId/filters")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $aliceToken")
        ).andExpect(status().isOk)
    }

    @Test
    fun `creating a session with an unknown genre id returns 400 and writes no session row`() {
        enqueueJson(GENRE_FIXTURE)
        val before = sessionRepository.count()

        mockMvc.perform(
            post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"genre":9999}""")
        )
            .andExpect(status().isBadRequest)

        assertEquals(before, sessionRepository.count())
    }
}
