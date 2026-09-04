package org.example.muvimatchr.catalog

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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

private const val GENRE_FIXTURE = """
{ "genres": [ {"id": 28, "name": "Action"} ] }
"""

private const val PROVIDERS_DE_FIXTURE = """
{ "results": [ {"provider_id": 8, "provider_name": "Netflix", "logo_path": "/netflix.jpg", "display_priority": 1} ] }
"""

@AutoConfigureMockMvc
class CatalogReferenceControllerTest : TmdbMockServerSupport() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var genreRepository: GenreRepository

    @Autowired
    lateinit var watchProviderRepository: WatchProviderRepository

    @BeforeEach
    fun clearReferenceTables() {
        genreRepository.deleteAll()
        watchProviderRepository.deleteAll()
    }

    private fun createSessionAndJoin(displayName: String): String {
        val session = mockMvc.perform(post("/api/sessions"))
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString
        @Suppress("UNCHECKED_CAST")
        val sessionBody = objectMapper.readValue(session, Map::class.java) as Map<String, Any>
        val joinCode = sessionBody["joinCode"] as String

        val joinResponse = mockMvc.perform(
            post("/api/sessions/$joinCode/participants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"$displayName"}""")
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString
        @Suppress("UNCHECKED_CAST")
        val joinBody = objectMapper.readValue(joinResponse, Map::class.java) as Map<String, Any>
        return joinBody["token"] as String
    }

    @Test
    fun `GET api catalog genres with a valid participant token returns the cached genre list with upstream id and name`() {
        val token = createSessionAndJoin("Alice")
        enqueueJson(GENRE_FIXTURE)

        val responseBody = mockMvc.perform(
            get("/api/catalog/genres")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        @Suppress("UNCHECKED_CAST")
        val genres = objectMapper.readValue(responseBody, List::class.java) as List<Map<String, Any>>
        assertEquals(1, genres.size)
        assertEquals(28, genres[0]["id"])
        assertEquals("Action", genres[0]["name"])
    }

    @Test
    fun `GET api catalog watch-providers with a valid participant token returns that region's provider list with logo path and display priority`() {
        val token = createSessionAndJoin("Bob")
        enqueueJson(PROVIDERS_DE_FIXTURE)

        val responseBody = mockMvc.perform(
            get("/api/catalog/watch-providers")
                .param("region", "DE")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        @Suppress("UNCHECKED_CAST")
        val providers = objectMapper.readValue(responseBody, List::class.java) as List<Map<String, Any>>
        assertEquals(1, providers.size)
        assertEquals(8, providers[0]["id"])
        assertEquals("Netflix", providers[0]["name"])
        assertEquals("/netflix.jpg", providers[0]["logoPath"])
        assertEquals(1, providers[0]["displayPriority"])
    }

    @Test
    fun `GET api catalog genres with no Authorization header returns 401`() {
        mockMvc.perform(get("/api/catalog/genres"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET api catalog watch-providers with an unrecognized bearer token returns 401`() {
        mockMvc.perform(
            get("/api/catalog/watch-providers")
                .param("region", "DE")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
        )
            .andExpect(status().isUnauthorized)
    }
}
