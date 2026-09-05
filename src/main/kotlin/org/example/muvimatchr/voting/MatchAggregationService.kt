package org.example.muvimatchr.voting

import org.example.muvimatchr.session.SessionRepository
import org.example.muvimatchr.session.SessionService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

// Pure read-model over the vote/participant/session tables. Holds no mutable state and caches
// nothing -- every value is recomputed from the database on every call (D-07 forbids a stored or
// sticky flag; an in-JVM counter or per-session progress map is the exact prior-prototype failure
// this rebuild exists to remove).
@Service
class MatchAggregationService(
    private val sessionRepository: SessionRepository,
    private val sessionService: SessionService,
    private val voteRepository: VoteRepository,
) {
    // D-05/D-06: a participant who joined but stopped voting is handled via a computed inactivity
    // timeout rather than "wait forever" or a manual exclude/kick action. Configurable so tests
    // can shrink the real one-minute window (04-CONTEXT.md D-06).
    @Value("\${voting.inactivity-timeout-seconds:60}")
    private var inactivityTimeoutSeconds: Int = 60

    fun computeStatus(sessionId: UUID): SessionVoteStatus {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No session with id $sessionId") }
        // Pitfall D: deckSize is always derived from the pinned snapshot itself, never from a
        // separate re-query against the catalog -- the two must never be able to independently
        // drift.
        val deckSize = sessionService.pinnedMovies(session).size
        val activeIds = voteRepository.findActiveParticipantIds(sessionId, inactivityTimeoutSeconds)

        // An empty active-participant collection would render an empty `IN ()` list, which
        // Postgres rejects -- short-circuit before issuing the second query.
        if (activeIds.isEmpty()) {
            return SessionVoteStatus(sessionId, deckSize, activeCount = 0, finishedCount = 0, isComplete = false)
        }

        val finishedCount = voteRepository.countFinishedParticipants(sessionId, activeIds, deckSize)
        // The deckSize > 0 conjunct is load-bearing: without it a freshly created, never-pinned
        // session (deckSize 0) would report itself complete the moment anyone is "active".
        val isComplete = deckSize > 0 && activeIds.isNotEmpty() && finishedCount == activeIds.size
        return SessionVoteStatus(sessionId, deckSize, activeCount = activeIds.size, finishedCount = finishedCount, isComplete = isComplete)
    }
}

data class SessionVoteStatus(
    val sessionId: UUID,
    val deckSize: Int,
    val activeCount: Int,
    val finishedCount: Int,
    val isComplete: Boolean,
)
