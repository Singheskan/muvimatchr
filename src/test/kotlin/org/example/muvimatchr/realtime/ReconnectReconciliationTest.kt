package org.example.muvimatchr.realtime

import org.example.muvimatchr.support.StompTestSupport
import org.example.muvimatchr.voting.MatchAggregationService
import org.example.muvimatchr.voting.VoteChoice
import org.example.muvimatchr.voting.VoteService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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

// Encodes RTIME-03 as executable assertions, driven entirely by a real STOMP client against a
// real embedded server (05-CONTEXT.md D-04) -- no manual HTML page, no manual-only verification
// step. See ARCHITECTURE.md Pattern 4 ("Reconnect-safe client, not reconnect-safe server") and
// PITFALLS.md Pitfall 5: the server offers nothing to be stale with -- no last-known-status
// cache, no per-client delivery ledger, no replay on resubscribe.
class ReconnectReconciliationTest : StompTestSupport() {

    @Autowired
    lateinit var voteService: VoteService

    @Autowired
    lateinit var matchAggregationService: MatchAggregationService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    // A plain JDK HttpClient against the real embedded server -- TestRestTemplate does not exist
    // on this project's Spring Boot 4.1.1 classpath (confirmed absent in 05-01; see
    // 05-01-SUMMARY.md). Zero new dependency, same approach 05-01 already established.
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    private val openClients = CopyOnWriteArrayList<Pair<WebSocketStompClient, StompSession>>()

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

    private fun fetchStatus(sessionId: UUID, rawToken: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/sessions/$sessionId/votes/status"))
            .GET()
        if (rawToken != null) {
            builder.header("Authorization", "Bearer $rawToken")
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun fetchAuthenticatedStatus(sessionId: UUID, rawToken: String): String {
        val response = fetchStatus(sessionId, rawToken)
        check(response.statusCode() == 200) {
            "expected 200 from GET /api/sessions/$sessionId/votes/status, got ${response.statusCode()}: ${response.body()}"
        }
        return response.body()
    }

    // Test A (RTIME-03, no-replay half): a client disconnected while the session completes
    // receives no replay on reconnect. A witness client stays connected throughout and records
    // every frame; the reconnecting client is explicitly disconnected before the completing votes
    // are recorded, then reconnects and resubscribes. Its queue must yield nothing -- proving the
    // server keeps no last-known-status buffer and no per-client delivery ledger. A future
    // "helpful" replay cache would break this test on purpose (ARCHITECTURE.md Pattern 4).
    @Test
    fun `a client disconnected across completion receives no replay on reconnect`() {
        val fixture = newSessionFixture(listOf("Alice", "Bob", "Carol"), deckSize = 1)
        val alice = requireNotNull(fixture.participants["Alice"])
        val bob = requireNotNull(fixture.participants["Bob"])
        val carol = requireNotNull(fixture.participants["Carol"])
        val movieId = fixture.movieIds.first()

        val witnessSession = connectAndTrack()
        val witnessQueue = subscribeAndAwaitReady(witnessSession, fixture.sessionId)

        val reconnectingClient = newStompClient()
        val reconnectingSession = connect(reconnectingClient)
        val reconnectingQueue = subscribeAndAwaitReady(reconnectingSession, fixture.sessionId)

        // Explicitly disconnect before any completing vote is recorded -- this client is now gone
        // and must receive nothing that happens while it's away.
        reconnectingSession.disconnect()
        reconnectingClient.stop()

        voteService.recordVote(fixture.sessionId, alice.id, movieId, VoteChoice.LIKE)
        assertNotNull(witnessQueue.poll(5, TimeUnit.SECONDS), "witness must see Alice's vote")
        voteService.recordVote(fixture.sessionId, bob.id, movieId, VoteChoice.LIKE)
        assertNotNull(witnessQueue.poll(5, TimeUnit.SECONDS), "witness must see Bob's vote")
        voteService.recordVote(fixture.sessionId, carol.id, movieId, VoteChoice.LIKE)
        val witnessCompletionFrame = witnessQueue.poll(5, TimeUnit.SECONDS)
        assertNotNull(witnessCompletionFrame, "witness must see the completion push")
        assertTrue(statusFieldsOf(witnessCompletionFrame!!)["isComplete"] as Boolean)

        // Reconnect the same participant with a brand-new StompSession and resubscribe.
        val freshSession = connectAndTrack()
        val freshQueue = subscribeAndAwaitReady(freshSession, fixture.sessionId)

        // The assertion that matters: nothing arrives. No replay, no last-known-state push.
        val replayed = freshQueue.poll(1, TimeUnit.SECONDS)
        assertNull(replayed, "a reconnecting client must never receive a replayed or remembered frame")
    }

    // Test B (RTIME-03, reconciliation half): the last frame the witness recorded and an
    // authenticated REST fetch must parse to the exact same map -- the client that missed every
    // push can ask the server once and land on precisely the state the last push carried. Also
    // pins the deliberate asymmetry (05-CONTEXT.md D-01/D-02 vs T-05-09): the topic is
    // unauthenticated by design, but the REST reconciliation path requires the participant's
    // token.
    @Test
    fun `REST reconciliation lands exactly on the last broadcast state and requires authentication`() {
        val fixture = newSessionFixture(listOf("Alice", "Bob"), deckSize = 1)
        val alice = requireNotNull(fixture.participants["Alice"])
        val bob = requireNotNull(fixture.participants["Bob"])
        val movieId = fixture.movieIds.first()

        val witnessSession = connectAndTrack()
        val witnessQueue = subscribeAndAwaitReady(witnessSession, fixture.sessionId)

        voteService.recordVote(fixture.sessionId, alice.id, movieId, VoteChoice.LIKE)
        assertNotNull(witnessQueue.poll(5, TimeUnit.SECONDS), "witness must see Alice's vote")

        voteService.recordVote(fixture.sessionId, bob.id, movieId, VoteChoice.LIKE)
        val lastFrameText = witnessQueue.poll(5, TimeUnit.SECONDS)
        assertNotNull(lastFrameText, "witness must see the completing push")
        val lastFrame = statusFieldsOf(lastFrameText!!)

        val restResponse = fetchStatus(fixture.sessionId, alice.rawToken)
        assertEquals(200, restResponse.statusCode(), "authenticated status fetch must return 200")
        val restMap = statusFieldsOf(restResponse.body())

        assertEquals(lastFrame.keys, restMap.keys, "reconciled REST body must expose the exact same key set as the last broadcast")
        for (key in lastFrame.keys) {
            assertEquals(lastFrame[key], restMap[key], "value for key '$key' must match between the last broadcast and the REST reconciliation body")
        }

        assertTrue(restMap["isComplete"] as Boolean, "REST body must report isComplete true")
        @Suppress("UNCHECKED_CAST")
        val matchedIds = restMap["matchedMovieIds"] as List<*>
        assertTrue(matchedIds.isNotEmpty(), "REST body must carry a non-empty matchedMovieIds")

        // The asymmetry: the topic itself is deliberately open (05-CONTEXT.md D-01/D-02), but the
        // REST reconciliation path must still require the participant's own token.
        val unauthenticatedResponse = fetchStatus(fixture.sessionId, rawToken = null)
        assertNotEquals(200, unauthenticatedResponse.statusCode(), "an unauthenticated status fetch must not return 200")
    }

    // Test C (RTIME-03, correctness-without-listeners half): the notification layer must never be
    // load-bearing for correctness. A session voted to completion with NO STOMP client connected
    // at all must still be correct, both via the direct computeStatus() read model and via an
    // authenticated REST fetch -- if broadcasting were somehow required for the state to settle,
    // this test fails. Also the cheapest possible guard against a future refactor moving
    // aggregation logic into realtime/.
    @Test
    fun `a session completes correctly even with no client ever subscribed`() {
        val fixture = newSessionFixture(listOf("Alice", "Bob"), deckSize = 1)
        val alice = requireNotNull(fixture.participants["Alice"])
        val bob = requireNotNull(fixture.participants["Bob"])
        val movieId = fixture.movieIds.first()

        // Deliberately: no connectAndTrack(), no subscribe, nothing. Just record votes.
        voteService.recordVote(fixture.sessionId, alice.id, movieId, VoteChoice.LIKE)
        voteService.recordVote(fixture.sessionId, bob.id, movieId, VoteChoice.LIKE)

        val directStatus = matchAggregationService.computeStatus(fixture.sessionId)
        assertTrue(directStatus.isComplete, "computeStatus must report completion with no listener ever subscribed")
        assertEquals(2, directStatus.activeCount)
        assertEquals(2, directStatus.finishedCount)
        assertTrue(directStatus.matchedMovieIds.contains(movieId))

        val restBody = fetchAuthenticatedStatus(fixture.sessionId, alice.rawToken)
        val restMap = statusFieldsOf(restBody)
        assertTrue(restMap["isComplete"] as Boolean, "REST fetch must also report completion with no listener ever subscribed")
        assertFalse((restMap["matchedMovieIds"] as List<*>).isEmpty())
    }
}
