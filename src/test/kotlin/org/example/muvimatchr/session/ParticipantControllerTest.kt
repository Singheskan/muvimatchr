package org.example.muvimatchr.session

import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
}
