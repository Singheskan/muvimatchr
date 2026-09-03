package org.example.muvimatchr.session

import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

@AutoConfigureMockMvc
class ParticipantControllerTest : PostgresTestSupport() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var participantRepository: ParticipantRepository

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
    fun `POST sessions twice returns two distinct non-blank join codes`() {
        val first = createSession()
        val second = createSession()

        val firstJoinCode = first["joinCode"] as String
        val secondJoinCode = second["joinCode"] as String

        assertTrue(firstJoinCode.isNotBlank())
        assertTrue(secondJoinCode.isNotBlank())
        assertNotEquals(firstJoinCode, secondJoinCode)
    }

    @Test
    fun `joining with a valid join code returns a server-issued token distinct from name and id, hashed at rest`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String

        val joinResponse = joinSession(joinCode, "Alice")

        val token = joinResponse["token"] as String
        val participantId = joinResponse["participantId"] as String
        assertNotEquals("Alice", token)
        assertNotEquals(participantId, token)

        val saved = participantRepository.findById(java.util.UUID.fromString(participantId))
        assertTrue(saved.isPresent)
        assertEquals(64, saved.get().tokenHash.length)
        assertNotEquals(token, saved.get().tokenHash)
    }

    @Test
    fun `joining with an unknown join code returns 404`() {
        mockMvc.perform(
            post("/api/sessions/ZZZZZZ/participants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Bob"}""")
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `me with a valid token returns the same participantId issued at join time`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val joinResponse = joinSession(joinCode, "Carol")
        val token = joinResponse["token"] as String
        val participantId = joinResponse["participantId"] as String

        mockMvc.perform(
            get("/api/sessions/$sessionId/participants/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.participantId").value(participantId))
    }

    @Test
    fun `me does not insert a new participant row on resume`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String
        val joinResponse = joinSession(joinCode, "Dave")
        val token = joinResponse["token"] as String

        val countBeforeResume = participantRepository.count()

        mockMvc.perform(
            get("/api/sessions/$sessionId/participants/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)

        assertEquals(countBeforeResume, participantRepository.count())
    }

    @Test
    fun `me with no Authorization header returns 401`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String

        mockMvc.perform(get("/api/sessions/$sessionId/participants/me"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `me with an unrecognized bearer token returns 401`() {
        val session = createSession()
        val sessionId = session["sessionId"] as String

        mockMvc.perform(
            get("/api/sessions/$sessionId/participants/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `joining with a blank display name returns 400 and does not persist a participant`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val countBefore = participantRepository.count()

        mockMvc.perform(
            post("/api/sessions/$joinCode/participants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":""}""")
        )
            .andExpect(status().isBadRequest)

        assertEquals(countBefore, participantRepository.count())
    }

    @Test
    fun `joining with a 101-character display name returns 400 and does not persist a participant`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val countBefore = participantRepository.count()
        val tooLongName = "A".repeat(101)

        mockMvc.perform(
            post("/api/sessions/$joinCode/participants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"$tooLongName"}""")
        )
            .andExpect(status().isBadRequest)

        assertEquals(countBefore, participantRepository.count())
    }

    @Test
    fun `joining with a valid display name still returns 201 after adding validation`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String

        val joinResponse = joinSession(joinCode, "Alice")

        assertEquals("Alice", joinResponse["displayName"])
    }

    @Test
    fun `me with a valid token but a different session's sessionId returns 404`() {
        val firstSession = createSession()
        val firstJoinCode = firstSession["joinCode"] as String
        val firstJoinResponse = joinSession(firstJoinCode, "Eve")
        val firstToken = firstJoinResponse["token"] as String

        val secondSession = createSession()
        val secondSessionId = secondSession["sessionId"] as String

        mockMvc.perform(
            get("/api/sessions/$secondSessionId/participants/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $firstToken")
        )
            .andExpect(status().isNotFound)
    }
}
