package org.example.muvimatchr.session

import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
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

@AutoConfigureMockMvc
class SessionFiltersTest : PostgresTestSupport() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var sessionRepository: SessionRepository

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
        val session = createSession("""{"region":"US","providerIds":[8,337]}""")

        assertEquals("US", session["region"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(8, 337), session["providerIds"] as List<Int>)
    }

    @Test
    fun `GET filters with a valid participant token returns the session's current region and provider ids`() {
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
        val session = createSession("""{"region":"US","providerIds":[8,9]}""")
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val token = joinSession(joinCode, "Alice")["token"] as String

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
        val firstSession = createSession("""{"region":"US","providerIds":[8]}""")
        val firstJoinCode = firstSession["joinCode"] as String
        val firstSessionId = firstSession["sessionId"] as String
        joinSession(firstJoinCode, "Alice")

        val secondSession = createSession()
        val secondJoinCode = secondSession["joinCode"] as String
        val secondToken = joinSession(secondJoinCode, "Bob")["token"] as String

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
        val session = createSession("""{"region":"US","providerIds":[8]}""")
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val token = joinSession(joinCode, "Alice")["token"] as String

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
        val session = createSession("""{"region":"US","providerIds":[8]}""")
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val token = joinSession(joinCode, "Alice")["token"] as String

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
}
