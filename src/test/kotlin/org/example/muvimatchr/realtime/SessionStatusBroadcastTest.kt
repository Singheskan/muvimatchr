package org.example.muvimatchr.realtime

import org.example.muvimatchr.support.StompTestSupport
import org.example.muvimatchr.voting.VoteChoice
import org.example.muvimatchr.voting.VoteService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.web.socket.messaging.WebSocketStompClient
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// Per 05-CONTEXT.md D-04, this phase's verification is automated-only through a real STOMP
// client against a real embedded server -- no throwaway manual HTML test page and no
// manual-only step. Every queue poll and future wait below is bounded by an explicit timeout,
// with one deliberate exception: Test D's negative wait, where the expected outcome IS the
// absence of a message, so a short bounded wait is the correct (not a shortcut) assertion shape.
class SessionStatusBroadcastTest : StompTestSupport() {

    @Autowired
    lateinit var voteService: VoteService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    // A plain JDK HttpClient against the real embedded server -- REST assertions in this class
    // need an actual HTTP round trip through the running application, not a MockMvc-simulated
    // request, so the REST read genuinely runs on its own connection independent of the STOMP
    // socket and the transaction that produced the pushed frame.
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    private val openClients = CopyOnWriteArrayList<Pair<WebSocketStompClient, StompSession>>()

    // A leaked connection could carry frames from one test into the next -- disconnect every
    // session this test opened, regardless of how the test finished.
    @AfterEach
    fun disconnectAll() {
        openClients.forEach { (client, session) ->
            runCatching { session.disconnect() }
            runCatching { client.stop() }
        }
        openClients.clear()
    }

    private fun connectAndTrack(): StompSession {
        val client = newStompClient()
        val session = connect(client)
        openClients.add(client to session)
        return session
    }

    private fun statusFieldsOf(json: String): Map<*, *> = objectMapper.readValue(json, Map::class.java)

    private fun fetchRestStatus(sessionId: UUID, rawToken: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/sessions/$sessionId/votes/status"))
            .header("Authorization", "Bearer $rawToken")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "expected 200 from GET /api/sessions/$sessionId/votes/status, got ${response.statusCode()}: ${response.body()}"
        }
        return response.body()
    }

    // Test A: a vote produces a push. The client issues no request of its own -- a non-null
    // poll is the proof that the after-commit push path works end to end (RTIME-01's server
    // half).
    @Test
    fun `recording a vote pushes a status frame to a subscribed client`() {
        val fixture = newSessionFixture(listOf("Alice", "Bob"), deckSize = 2)
        val alice = requireNotNull(fixture.participants["Alice"])
        val firstMovieId = fixture.movieIds.first()

        val session = connectAndTrack()
        val queue: LinkedBlockingQueue<String> = subscribeAndAwaitReady(session, fixture.sessionId)

        voteService.recordVote(fixture.sessionId, alice.id, firstMovieId, VoteChoice.LIKE)

        val frame = queue.poll(5, TimeUnit.SECONDS)
        assertNotNull(frame, "expected a pushed status frame after recordVote, none arrived within the timeout")

        val parsed = statusFieldsOf(frame!!)
        assertEquals(fixture.sessionId.toString(), parsed["sessionId"])
        assertNotNull(parsed["finishedCount"], "finishedCount must be present on the pushed frame")
        assertNotNull(parsed["activeCount"], "activeCount must be present on the pushed frame")
        assertNotNull(parsed["deckSize"], "deckSize must be present on the pushed frame")
    }

    // Test B: the pushed JSON is the REST JSON, field-for-field, including the isComplete wire
    // name -- this is what catches Kotlin's `is`-prefix boolean-getter stripping, which would
    // otherwise silently give the WS and REST payloads different field names (RTIME-03's
    // server-side precondition).
    @Test
    fun `the pushed frame JSON is identical to the REST status JSON, including the isComplete field name`() {
        val fixture = newSessionFixture(listOf("Alice"), deckSize = 2)
        val alice = requireNotNull(fixture.participants["Alice"])
        val firstMovieId = fixture.movieIds.first()

        val session = connectAndTrack()
        val queue: LinkedBlockingQueue<String> = subscribeAndAwaitReady(session, fixture.sessionId)

        voteService.recordVote(fixture.sessionId, alice.id, firstMovieId, VoteChoice.LIKE)

        val pushedText = queue.poll(5, TimeUnit.SECONDS)
        assertNotNull(pushedText, "expected a pushed status frame, none arrived within the timeout")

        val restText = fetchRestStatus(fixture.sessionId, alice.rawToken)

        val pushedMap = statusFieldsOf(pushedText!!)
        val restMap = statusFieldsOf(restText)

        assertEquals(restMap.keys, pushedMap.keys, "pushed and REST payloads must expose the exact same key set")
        for (key in restMap.keys) {
            assertEquals(restMap[key], pushedMap[key], "value for key '$key' must match between pushed and REST payloads")
        }

        assertTrue(pushedText.contains("\"isComplete\":"), "pushed frame must serialize the completion flag under the name isComplete")
        assertFalse(pushedText.contains("\"complete\":"), "pushed frame must NOT contain a top-level 'complete' key (the Kotlin is-prefix stripping bug)")
    }

    // Test C: the push follows the commit. A push emitted inside the still-open transaction
    // would fail this -- the REST read runs on a different connection and, under READ
    // COMMITTED, would see the pre-commit state: a lower finishedCount than the frame the client
    // just acted on.
    @Test
    fun `a REST status fetch issued the instant a push arrives never reports an older state than the push`() {
        val fixture = newSessionFixture(listOf("Alice"), deckSize = 1)
        val alice = requireNotNull(fixture.participants["Alice"])
        val movieId = fixture.movieIds.first()

        val session = connectAndTrack()
        val queue: LinkedBlockingQueue<String> = subscribeAndAwaitReady(session, fixture.sessionId)

        voteService.recordVote(fixture.sessionId, alice.id, movieId, VoteChoice.LIKE)

        val pushedText = queue.poll(5, TimeUnit.SECONDS)
        assertNotNull(pushedText, "expected a pushed status frame, none arrived within the timeout")
        val pushedMap = statusFieldsOf(pushedText!!)

        val restText = fetchRestStatus(fixture.sessionId, alice.rawToken)
        val restMap = statusFieldsOf(restText)

        val pushedFinished = (pushedMap["finishedCount"] as Number).toInt()
        val restFinished = (restMap["finishedCount"] as Number).toInt()
        assertTrue(
            restFinished >= pushedFinished,
            "REST finishedCount ($restFinished) must never be less than the pushed frame's finishedCount ($pushedFinished)",
        )

        val pushedComplete = pushedMap["isComplete"] as Boolean
        val restComplete = restMap["isComplete"] as Boolean
        if (pushedComplete) {
            assertTrue(restComplete, "REST isComplete must not be false when the pushed frame already said true")
        }
    }

    // Test D: a client cannot forge a broadcast. Proves the inbound interceptor from Task 1 is
    // actually installed -- a party who merely knows a session UUID cannot inject a fake status
    // frame into other participants' clients.
    @Test
    fun `a forged client SEND to a session topic reaches no subscriber`() {
        val fixture = newSessionFixture(listOf("Alice"), deckSize = 1)

        val subscriberSession = connectAndTrack()
        val queue: LinkedBlockingQueue<String> = subscribeAndAwaitReady(subscriberSession, fixture.sessionId)

        val attackerSession = connectAndTrack()
        val forgedPayload = """{"sessionId":"${fixture.sessionId}","deckSize":1,"activeCount":1,"finishedCount":1,"isComplete":true,"matchedMovieIds":[999],"likeCounts":[]}"""
        // Sent as raw bytes, not a String: the client's default SimpleMessageConverter performs
        // no serialization of its own, so a String payload here would fail with a
        // ClassCastException deep in the STOMP encoder rather than exercising the send path this
        // test needs -- the encoder always expects an already-encoded byte[] payload.
        attackerSession.send("/topic/session/${fixture.sessionId}", forgedPayload.toByteArray(Charsets.UTF_8))

        val frame = queue.poll(1, TimeUnit.SECONDS)
        assertNull(frame, "a forged client SEND must never reach a session topic's subscribers")
    }
}
