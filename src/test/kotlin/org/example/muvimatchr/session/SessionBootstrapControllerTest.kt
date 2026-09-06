package org.example.muvimatchr.session

import org.example.muvimatchr.support.PostgresTestSupport
import org.example.muvimatchr.voting.VoteChoice
import org.example.muvimatchr.voting.VoteRepository
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.util.UUID

@AutoConfigureMockMvc
class SessionBootstrapControllerTest : PostgresTestSupport() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var voteRepository: VoteRepository

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
    fun `by-code me with a valid token returns the participant's own session, id and name`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val joinResponse = joinSession(joinCode, "Alice")
        val token = joinResponse["token"] as String
        val participantId = joinResponse["participantId"] as String
        val sessionId = session["sessionId"] as String

        mockMvc.perform(
            get("/api/sessions/by-code/$joinCode/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sessionId").value(sessionId))
            .andExpect(jsonPath("$.joinCode").value(joinCode))
            .andExpect(jsonPath("$.participantId").value(participantId))
            .andExpect(jsonPath("$.displayName").value("Alice"))
            .andExpect(jsonPath("$.deckPinned").value(false))
            .andExpect(jsonPath("$.votedMovieIds").isArray)
    }

    @Test
    fun `by-code me with a token whose session has a different join code returns 404`() {
        val firstSession = createSession()
        val firstJoinCode = firstSession["joinCode"] as String
        val firstJoinResponse = joinSession(firstJoinCode, "Bob")
        val firstToken = firstJoinResponse["token"] as String

        val secondSession = createSession()
        val secondJoinCode = secondSession["joinCode"] as String

        mockMvc.perform(
            get("/api/sessions/by-code/$secondJoinCode/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $firstToken")
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `by-code me with no Authorization header returns 401`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String

        mockMvc.perform(get("/api/sessions/by-code/$joinCode/me"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `votedMovieIds reflects only the caller's own votes, never another participant's`() {
        val session = createSession()
        val joinCode = session["joinCode"] as String
        val sessionId = session["sessionId"] as String

        val alice = joinSession(joinCode, "Alice")
        val aliceToken = alice["token"] as String
        val aliceId = alice["participantId"] as String

        val bob = joinSession(joinCode, "Bob")
        val bobToken = bob["token"] as String
        val bobId = bob["participantId"] as String

        // Seeded directly via VoteRepository.upsertVote (mirrors VoteRepositoryTest's precedent)
        // rather than through POST /votes, which requires a pinned deck as a precondition -- this
        // test only needs vote rows to exist, not a full deck-pin flow.
        voteRepository.upsertVote(UUID.randomUUID(), UUID.fromString(sessionId), UUID.fromString(aliceId), 101L, VoteChoice.LIKE.name)
        voteRepository.upsertVote(UUID.randomUUID(), UUID.fromString(sessionId), UUID.fromString(aliceId), 102L, VoteChoice.PASS.name)
        voteRepository.upsertVote(UUID.randomUUID(), UUID.fromString(sessionId), UUID.fromString(bobId), 201L, VoteChoice.LIKE.name)

        mockMvc.perform(
            get("/api/sessions/by-code/$joinCode/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $aliceToken")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.votedMovieIds", org.hamcrest.Matchers.containsInAnyOrder(101, 102)))
    }
}
