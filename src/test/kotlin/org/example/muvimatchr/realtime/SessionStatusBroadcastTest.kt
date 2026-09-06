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

    // Test E (RTIME-01): waiting on N of M advances with every finish. Three participants, two
    // pinned movies -- everyone votes the first movie together (uninteresting), then votes the
    // second movie one at a time so the finishedCount progression 1, 2, 3 is the signal under
    // test. Votes are driven strictly sequentially from this thread on purpose: the in-memory
    // SimpleBroker dispatches on a pooled executor, so ordering between two broadcasts produced
    // by two SIMULTANEOUSLY committing votes is not guaranteed, and asserting an order over a
    // concurrent finish here would be a flaky test asserting a property the architecture
    // deliberately does not provide (ARCHITECTURE.md Pattern 4).
    @Test
    fun `waiting count advances by exactly one with each sequential finish`() {
        val fixture = newSessionFixture(listOf("Alice", "Bob", "Carol"), deckSize = 2)
        val alice = requireNotNull(fixture.participants["Alice"])
        val bob = requireNotNull(fixture.participants["Bob"])
        val carol = requireNotNull(fixture.participants["Carol"])
        val (firstMovieId, secondMovieId) = fixture.movieIds

        val session = connectAndTrack()
        val queue: LinkedBlockingQueue<String> = subscribeAndAwaitReady(session, fixture.sessionId)

        // Round 1: nobody has voted every movie yet, so no participant is "finished" -- these
        // pushes exist but carry no signal for this test.
        voteService.recordVote(fixture.sessionId, alice.id, firstMovieId, VoteChoice.LIKE)
        assertNotNull(queue.poll(5, TimeUnit.SECONDS), "expected a push after Alice's first-round vote")
        voteService.recordVote(fixture.sessionId, bob.id, firstMovieId, VoteChoice.LIKE)
        assertNotNull(queue.poll(5, TimeUnit.SECONDS), "expected a push after Bob's first-round vote")
        voteService.recordVote(fixture.sessionId, carol.id, firstMovieId, VoteChoice.LIKE)
        assertNotNull(queue.poll(5, TimeUnit.SECONDS), "expected a push after Carol's first-round vote")

        // Round 2: each vote here completes that participant's deck, so finishedCount must climb
        // 1, then 2, then 3 -- equality, not a bound, so a skipped or duplicated value fails.
        voteService.recordVote(fixture.sessionId, alice.id, secondMovieId, VoteChoice.LIKE)
        val afterAlice = queue.poll(5, TimeUnit.SECONDS)
        assertNotNull(afterAlice, "expected a push naming Alice's finishing vote, none arrived")
        val aliceFrame = statusFieldsOf(afterAlice!!)

        voteService.recordVote(fixture.sessionId, bob.id, secondMovieId, VoteChoice.LIKE)
        val afterBob = queue.poll(5, TimeUnit.SECONDS)
        assertNotNull(afterBob, "expected a push naming Bob's finishing vote, none arrived")
        val bobFrame = statusFieldsOf(afterBob!!)

        voteService.recordVote(fixture.sessionId, carol.id, secondMovieId, VoteChoice.LIKE)
        val afterCarol = queue.poll(5, TimeUnit.SECONDS)
        assertNotNull(afterCarol, "expected a push naming Carol's finishing vote, none arrived")
        val carolFrame = statusFieldsOf(afterCarol!!)

        assertEquals(3, (aliceFrame["activeCount"] as Number).toInt())
        assertEquals(3, (bobFrame["activeCount"] as Number).toInt())
        assertEquals(3, (carolFrame["activeCount"] as Number).toInt())

        assertEquals(1, (aliceFrame["finishedCount"] as Number).toInt(), "finishedCount must equal 1 after Alice's finishing vote")
        assertEquals(2, (bobFrame["finishedCount"] as Number).toInt(), "finishedCount must equal 2 after Bob's finishing vote")
        assertEquals(3, (carolFrame["finishedCount"] as Number).toInt(), "finishedCount must equal 3 after Carol's finishing vote")

        assertFalse(aliceFrame["isComplete"] as Boolean, "session must not be complete after only 1 of 3 finished")
        assertFalse(bobFrame["isComplete"] as Boolean, "session must not be complete after only 2 of 3 finished")
    }

    // Test F (RTIME-02): the completion push fans out to every connected client. Two
    // participants, one pinned movie, so the second vote both completes the session and produces
    // the payload every connected client must receive identically -- a fan-out that personalised
    // the payload per connection would violate the aggregate-only payload constraint (T-05-02).
    @Test
    fun `the completion push reaches every connected client with an identical payload`() {
        val fixture = newSessionFixture(listOf("Alice", "Bob"), deckSize = 1)
        val alice = requireNotNull(fixture.participants["Alice"])
        val bob = requireNotNull(fixture.participants["Bob"])
        val movieId = fixture.movieIds.first()

        val sessionOne = connectAndTrack()
        val queueOne = subscribeAndAwaitReady(sessionOne, fixture.sessionId)
        val sessionTwo = connectAndTrack()
        val queueTwo = subscribeAndAwaitReady(sessionTwo, fixture.sessionId)

        voteService.recordVote(fixture.sessionId, alice.id, movieId, VoteChoice.LIKE)
        // Drain the non-completing first push from both queues before the completing vote.
        queueOne.poll(5, TimeUnit.SECONDS)
        queueTwo.poll(5, TimeUnit.SECONDS)

        voteService.recordVote(fixture.sessionId, bob.id, movieId, VoteChoice.LIKE)

        val frameOneText = queueOne.poll(5, TimeUnit.SECONDS)
        val frameTwoText = queueTwo.poll(5, TimeUnit.SECONDS)
        assertNotNull(frameOneText, "expected the completion push on the first connected client")
        assertNotNull(frameTwoText, "expected the completion push on the second connected client")

        val frameOne = statusFieldsOf(frameOneText!!)
        val frameTwo = statusFieldsOf(frameTwoText!!)

        assertTrue(frameOne["isComplete"] as Boolean, "first client's completion frame must report isComplete true")
        assertTrue(frameTwo["isComplete"] as Boolean, "second client's completion frame must report isComplete true")
        assertEquals(frameOne["activeCount"], frameOne["finishedCount"], "finishedCount must equal activeCount at completion")
        assertEquals(frameTwo["activeCount"], frameTwo["finishedCount"], "finishedCount must equal activeCount at completion")

        @Suppress("UNCHECKED_CAST")
        val matchedIdsOne = frameOne["matchedMovieIds"] as List<*>
        assertTrue(
            matchedIdsOne.any { (it as Number).toLong() == movieId },
            "completion frame must carry the pinned movie id as a unanimous match",
        )

        // The whole server-side content of RTIME-02: both connections must be handed the exact
        // same aggregate frame, with no per-connection personalization.
        assertEquals(frameOne, frameTwo, "fan-out must deliver an identical payload to every connected client")
    }

    // Test G: a subscriber only hears its own session. Negative half proves the destination is
    // genuinely keyed per session rather than a shared channel; positive half proves the negative
    // result was not simply a dead subscription.
    @Test
    fun `a subscriber to one session receives nothing from another session and does receive its own`() {
        val sessionA = newSessionFixture(listOf("Alice", "Bob"), deckSize = 1)
        val sessionB = newSessionFixture(listOf("Carol", "Dave"), deckSize = 1)

        val subscriberSession = connectAndTrack()
        val queue = subscribeAndAwaitReady(subscriberSession, sessionA.sessionId)

        // Complete session B entirely -- session A's subscriber must hear none of it.
        val bParticipants = sessionB.participants.values.toList()
        val bMovieId = sessionB.movieIds.first()
        bParticipants.forEach { p -> voteService.recordVote(sessionB.sessionId, p.id, bMovieId, VoteChoice.LIKE) }

        val crossSessionFrame = queue.poll(1, TimeUnit.SECONDS)
        assertNull(crossSessionFrame, "a session-A subscriber must receive nothing while session B completes")

        // Now vote in session A itself -- the same subscriber must receive its own session's frame.
        val alice = requireNotNull(sessionA.participants["Alice"])
        voteService.recordVote(sessionA.sessionId, alice.id, sessionA.movieIds.first(), VoteChoice.LIKE)
        val ownSessionFrame = queue.poll(5, TimeUnit.SECONDS)
        assertNotNull(ownSessionFrame, "a session-A subscriber must receive its own session's push")
        val parsed = statusFieldsOf(ownSessionFrame!!)
        assertEquals(sessionA.sessionId.toString(), parsed["sessionId"], "the received frame must carry session A's own sessionId")
    }
}
