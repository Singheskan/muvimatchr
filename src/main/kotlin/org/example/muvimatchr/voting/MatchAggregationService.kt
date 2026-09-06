package org.example.muvimatchr.voting

import org.example.muvimatchr.session.ParticipantRepository
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
    private val participantRepository: ParticipantRepository,
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
        // Postgres rejects -- short-circuit before issuing the second query. It is also the
        // correct answer: with nobody present there is nothing everybody agreed on, and
        // like-counts (unfiltered by roster) are still computed below.
        if (activeIds.isEmpty()) {
            return SessionVoteStatus(
                sessionId,
                deckSize,
                activeCount = 0,
                finishedCount = 0,
                isComplete = false,
                matchedMovieIds = emptyList(),
                likeCounts = perMovieLikeCounts(sessionId),
            )
        }

        val finishedCount = voteRepository.countFinishedParticipants(sessionId, activeIds, deckSize)
        // The deckSize > 0 conjunct is load-bearing: without it a freshly created, never-pinned
        // session (deckSize 0) would report itself complete the moment anyone is "active".
        val isComplete = deckSize > 0 && activeIds.isNotEmpty() && finishedCount == activeIds.size
        return SessionVoteStatus(
            sessionId,
            deckSize,
            activeCount = activeIds.size,
            finishedCount = finishedCount,
            isComplete = isComplete,
            matchedMovieIds = unanimousMovieIds(sessionId, activeIds),
            likeCounts = perMovieLikeCounts(sessionId),
        )
    }

    // VOTE-04: an empty active-participant collection would render an empty `IN ()` list, which
    // Postgres rejects -- this early return is required, not defensive, and is also the correct
    // answer: with nobody present there is nothing everybody agreed on.
    fun unanimousMovieIds(sessionId: UUID, activeParticipantIds: List<UUID>): List<Long> {
        if (activeParticipantIds.isEmpty()) return emptyList()
        return voteRepository.findUnanimousMovieIds(
            sessionId,
            activeParticipantIds,
            activeParticipantIds.size,
            VoteChoice.LIKE.name,
        )
    }

    // RSLT-03: deliberately unfiltered by active roster or unanimity -- an idle participant's
    // earlier likes still count toward every movie's tally (D-07).
    fun perMovieLikeCounts(sessionId: UUID): List<MovieLikeCount> =
        voteRepository.findLikeCountsBySession(sessionId, VoteChoice.LIKE.name)
            .map { row -> MovieLikeCount((row[0] as Number).toLong(), (row[1] as Number).toInt()) }

    // RSLT-01/D-10: the named per-person roster the waiting screen needs. Reuses the same
    // inactivity rule and deckSize source computeStatus already uses -- see the class-level
    // caching prohibition and the findActiveParticipantIds call below, which must remain the
    // single place the inactivity expression lives.
    fun computeRoster(sessionId: UUID): SessionRoster {
        val session = sessionRepository.findById(sessionId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No session with id $sessionId") }
        // Pitfall D, same guard as computeStatus: deckSize is always derived from the pinned
        // snapshot, never a separate catalog re-query.
        val deckSize = sessionService.pinnedMovies(session).size
        // Call the shared query; do not restate the inactivity rule here. The fallback expression
        // inside that repository query (last vote time, falling back to join time) is D-06 stated
        // exactly once, and a second copy anywhere in this service would be free to drift from the
        // one the completion arithmetic uses.
        val activeIds = voteRepository.findActiveParticipantIds(sessionId, inactivityTimeoutSeconds).toSet()
        val votedCounts = voteRepository.findVoteCountsByParticipant(sessionId)
            .associate { row -> (row[0] as UUID) to (row[1] as Number).toInt() }

        val participants = participantRepository.findBySession_IdOrderByCreatedAtAsc(sessionId).map { p ->
            val votedCount = votedCounts[p.id] ?: 0
            ParticipantProgress(
                participantId = p.id!!,
                displayName = p.displayName,
                votedCount = votedCount,
                // Load-bearing deckSize > 0 conjunct, same as computeStatus: without it a
                // never-pinned session would report every zero-vote participant as finished.
                isFinished = deckSize > 0 && votedCount >= deckSize,
                isActive = p.id in activeIds,
            )
        }
        return SessionRoster(sessionId, deckSize, participants)
    }
}

data class MovieLikeCount(
    val movieId: Long,
    val likeCount: Int,
)

data class SessionVoteStatus(
    val sessionId: UUID,
    val deckSize: Int,
    val activeCount: Int,
    val finishedCount: Int,
    val isComplete: Boolean,
    val matchedMovieIds: List<Long>,
    val likeCounts: List<MovieLikeCount>,
)

data class ParticipantProgress(
    val participantId: UUID,
    val displayName: String,
    val votedCount: Int,
    val isFinished: Boolean,
    val isActive: Boolean,
)

data class SessionRoster(
    val sessionId: UUID,
    val deckSize: Int,
    val participants: List<ParticipantProgress>,
)
