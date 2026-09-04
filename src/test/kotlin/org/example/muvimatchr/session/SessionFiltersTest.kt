package org.example.muvimatchr.session

import org.example.muvimatchr.catalog.WatchProviderRepository
import org.example.muvimatchr.support.TmdbMockServerSupport
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

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

    // Lazy-on-miss like the deck cache and genre validation domain; clearing before every test
    // keeps the "which region has already been fetched" state deterministic across test order.
    @BeforeEach
    fun clearWatchProviders() {
        watchProviderRepository.deleteAll()
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
}
