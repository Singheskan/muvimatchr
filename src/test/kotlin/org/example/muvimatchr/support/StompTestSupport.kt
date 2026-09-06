package org.example.muvimatchr.support

import org.example.muvimatchr.catalog.MovieCatalogService.CachedMovie
import org.example.muvimatchr.session.JoinResult
import org.example.muvimatchr.session.ParticipantService
import org.example.muvimatchr.session.SessionService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// Deliberately drives a real broker over a real socket against real Postgres. A mocked
// SimpMessagingTemplate would only assert that a method was called -- it would prove nothing
// about the endpoint path, the broker prefix, the destination string, or the actual serialized
// field names, which is exactly what this phase's parity and forgery tests need to exercise.
//
// @SpringBootTest here overrides the MOCK web environment inherited from PostgresTestSupport
// with a real embedded servlet container (RANDOM_PORT), so a real STOMP handshake can occur.
// This subclass declares no PostgreSQLContainer of its own -- the singleton container and its
// @ServiceConnection wiring both come from PostgresTestSupport's companion object.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class StompTestSupport : PostgresTestSupport() {

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var sessionService: SessionService

    @Autowired
    protected lateinit var participantService: ParticipantService

    @Autowired
    protected lateinit var messagingTemplate: SimpMessagingTemplate

    // Distinct numeric movie-id range per fixture invocation so no two fixtures -- even across
    // tests in the same class -- ever collide on the same pinned movie ids.
    private val movieIdRangeStart = AtomicLong(10_000_000L)

    /**
     * Builds a fresh WebSocketStompClient with a started task scheduler (so STOMP heartbeats have
     * somewhere to run). Deliberately leaves the client's default message converter
     * (SimpleMessageConverter) in place rather than installing StringMessageConverter: that
     * converter's mime-type matching rejects any frame whose content-type isn't text/plain, and
     * the broker's Jackson-based converter tags a broadcast object's frame as application/json --
     * so a StringMessageConverter would silently drop every real status push while still
     * accepting a plain-string test frame, which is exactly the failure mode this comment exists
     * to head off. SimpleMessageConverter performs no mime-type filtering at all: requesting a
     * byte[] payload type (see subscribeToSessionTopic below) always returns the raw wire bytes
     * regardless of content-type, which is also the raw-JSON-on-the-wire behavior this phase's
     * parity assertions need.
     */
    protected fun newStompClient(): WebSocketStompClient {
        val client = WebSocketStompClient(StandardWebSocketClient())
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.afterPropertiesSet()
        client.taskScheduler = scheduler
        return client
    }

    /** Connects via connectAsync (not the deprecated blocking connect) and blocks with a bounded timeout. */
    protected fun connect(client: WebSocketStompClient): StompSession =
        client
            .connectAsync("ws://localhost:$port/ws", object : StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS)

    // Shared sink for readiness-handshake marker frames (see subscribeAndAwaitReady below),
    // deliberately separate from any per-subscription "real" queue. Two-concurrent-subscriber
    // tests (05-02) revealed that without this split, a SECOND client's readiness marker --
    // broadcast to the whole topic via messagingTemplate, not addressed to just that client --
    // also lands in a FIRST, already-subscribed client's queue, corrupting it with a non-JSON
    // string the first client's test code never asked for and cannot parse as a status frame.
    // Routing every marker-prefixed frame here instead, regardless of which subscription received
    // it, keeps every "real" queue returned to callers free of handshake noise.
    private val readyMarkerFrames = LinkedBlockingQueue<String>()

    /**
     * Subscribes to a session's status topic and returns a queue that fills with the raw JSON
     * frame body as a String. Requests a ByteArray payload type -- not String -- because the
     * client's default SimpleMessageConverter only ever hands back a payload that is already
     * assignable to the requested type with zero conversion, and the STOMP frame's payload is
     * always raw bytes on the wire regardless of what type the server originally published. No
     * client-side deserialization to a DTO happens here -- that's deliberate, the parity
     * assertion needs the actual wire field names untouched. Any frame tagged with the readiness
     * marker prefix is diverted to [readyMarkerFrames] instead of this queue -- see that field's
     * comment for why.
     */
    protected fun subscribeToSessionTopic(session: StompSession, sessionId: UUID): LinkedBlockingQueue<String> {
        val queue = LinkedBlockingQueue<String>()
        session.subscribe(
            "/topic/session/$sessionId",
            object : StompFrameHandler {
                override fun getPayloadType(headers: StompHeaders): Type = ByteArray::class.java

                override fun handleFrame(headers: StompHeaders, payload: Any?) {
                    val text = when (payload) {
                        is ByteArray -> String(payload, Charsets.UTF_8)
                        is String -> payload
                        else -> null
                    } ?: return
                    if (text.startsWith(READY_MARKER_PREFIX)) {
                        readyMarkerFrames.offer(text)
                    } else {
                        queue.offer(text)
                    }
                }
            },
        )
        return queue
    }

    /**
     * Subscribes to a session's status topic and blocks until the subscription is actually live
     * on the broker before returning -- not a fixed-duration sleep, but a bounded retry loop that
     * repeatedly publishes a disposable, uniquely-tagged marker frame to the same topic (via the
     * server-side [messagingTemplate], never a client SEND) and polls for it. STOMP gives no
     * client-visible acknowledgement that a SUBSCRIBE frame has been processed server-side; a
     * caller that published a real event immediately after calling subscribe (client-side
     * `subscribe()` returns as soon as the frame is queued for send, not once the broker has
     * registered it) can lose that event to exactly this race. This marker handshake is
     * deterministic and bounded rather than a sleep, and is safe to use here because nothing else
     * publishes to a brand-new session's topic before its first real vote. Polls the shared
     * [readyMarkerFrames] sink, not the returned queue, so a concurrently-subscribing second
     * client's handshake can never be mistaken for -- or pollute -- this call's own result.
     */
    protected fun subscribeAndAwaitReady(session: StompSession, sessionId: UUID): LinkedBlockingQueue<String> {
        val queue = subscribeToSessionTopic(session, sessionId)
        val readyMarker = "$READY_MARKER_PREFIX${UUID.randomUUID()}"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            messagingTemplate.convertAndSend("/topic/session/$sessionId", readyMarker)
            val frame = readyMarkerFrames.poll(200, TimeUnit.MILLISECONDS)
            if (frame == readyMarker) {
                return queue
            }
            // A non-matching marker here belongs to a different subscription's own handshake
            // (e.g. a second client subscribing to the same topic) -- discard it and keep
            // retrying with our own marker; the loop is bounded so this cannot spin forever.
        }
        error("STOMP subscription to /topic/session/$sessionId never became ready within the timeout")
    }

    private companion object {
        private const val READY_MARKER_PREFIX = "__stomp_test_ready__"
    }

    protected fun cachedMovie(movieId: Long): CachedMovie = CachedMovie(
        tmdbId = movieId,
        title = "Movie $movieId",
        posterPath = null,
        genreIds = emptyList(),
        voteAverage = 7.0,
        releaseDate = "2020-01-01",
        overview = null,
    )

    protected data class ParticipantFixture(val id: UUID, val rawToken: String)

    protected data class SessionFixture(
        val sessionId: UUID,
        val participants: Map<String, ParticipantFixture>,
        val movieIds: List<Long>,
    )

    /**
     * Creates a session through real beans (SessionService/ParticipantService), joins each named
     * participant so their raw bearer token is available (a direct repository save stores only a
     * hash with no known preimage), and pins a deck of [deckSize] movies drawn from this
     * invocation's own numeric range.
     */
    protected fun newSessionFixture(participantNames: List<String>, deckSize: Int): SessionFixture {
        val session = sessionService.createSession(null, emptyList())
        val sessionId = requireNotNull(session.id)

        val participants = participantNames.associateWith { name ->
            val joined: JoinResult = participantService.join(session.joinCode, name)
            ParticipantFixture(requireNotNull(joined.participant.id), joined.rawToken)
        }

        val rangeStart = movieIdRangeStart.getAndAdd(1000)
        val movieIds = (0 until deckSize).map { rangeStart + it }
        sessionService.pinDeck(sessionId, null, movieIds.map(::cachedMovie))

        return SessionFixture(sessionId, participants, movieIds)
    }
}
