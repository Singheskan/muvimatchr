package org.example.muvimatchr.voting

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import org.example.muvimatchr.auth.CurrentParticipant
import org.example.muvimatchr.session.Participant
import org.example.muvimatchr.session.SessionRepository
import org.example.muvimatchr.session.SessionService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/sessions")
class VoteController(
    private val sessionRepository: SessionRepository,
    private val sessionService: SessionService,
    private val voteService: VoteService,
    private val matchAggregationService: MatchAggregationService,
) {

    // Guard order is fixed and load-bearing: membership (404) before session load, unpinned deck
    // (409) before movie-membership, movie-membership (400) before any call into VoteService --
    // each later guard reads state the earlier one establishes, and no rejected request may ever
    // reach the transactional write path. Participant identity comes only from the resolved
    // bearer token (@CurrentParticipant) -- never accepted from the body or the query string
    // (T-04-01).
    @PostMapping("/{sessionId}/votes")
    fun recordVote(
        @PathVariable sessionId: UUID,
        @Valid @RequestBody request: VoteRequest,
        @CurrentParticipant participant: Participant,
    ): VoteStatusResponse {
        if (participant.session.id != sessionId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
        }
        val session = sessionRepository.findById(sessionId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No such session")
        }
        if (session.deckPinnedAt == null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "No deck has been pinned for this session yet")
        }
        // T-04-02: the client is never trusted to submit only ids it was shown -- reject any
        // movieId absent from the pinned snapshot before entering VoteService.
        if (request.movieId !in sessionService.pinnedMovieIds(session)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Movie is not part of this session's deck")
        }
        val status = voteService.recordVote(sessionId, participant.id!!, request.movieId, request.choice)
        return status.toResponse()
    }

    @GetMapping("/{sessionId}/votes/status")
    fun getStatus(
        @PathVariable sessionId: UUID,
        @CurrentParticipant participant: Participant,
    ): VoteStatusResponse {
        if (participant.session.id != sessionId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
        }
        return matchAggregationService.computeStatus(sessionId).toResponse()
    }

    // RSLT-01/D-10, T-06-06: identical membership guard to recordVote/getStatus -- membership
    // (404) is checked before any data is computed or returned. Deliberately does NOT return a
    // session-level completion flag (P-02); that authority lives only on VoteStatusResponse.
    @GetMapping("/{sessionId}/votes/roster")
    fun getRoster(
        @PathVariable sessionId: UUID,
        @CurrentParticipant participant: Participant,
    ): SessionRosterResponse {
        if (participant.session.id != sessionId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
        }
        return matchAggregationService.computeRoster(sessionId).toResponse()
    }
}

// Top-level (not a VoteController member) so SessionEventPublisher can map the same
// SessionVoteStatus to the same VoteStatusResponse shape the REST endpoint returns -- this is
// what makes the WS payload and the REST payload the same object by construction rather than by
// convention. Same file, same package, no visibility modifier needed by either call site.
fun SessionVoteStatus.toResponse() =
    VoteStatusResponse(
        sessionId = sessionId,
        deckSize = deckSize,
        activeCount = activeCount,
        finishedCount = finishedCount,
        isComplete = isComplete,
        matchedMovieIds = matchedMovieIds,
        likeCounts = likeCounts.map { MovieLikeCountResponse(it.movieId, it.likeCount) },
    )

// Top-level, mirroring SessionVoteStatus.toResponse() above -- consistent with the file's shape.
fun SessionRoster.toResponse() =
    SessionRosterResponse(
        sessionId = sessionId,
        deckSize = deckSize,
        participants = participants.map {
            ParticipantProgressResponse(
                participantId = it.participantId,
                displayName = it.displayName,
                votedCount = it.votedCount,
                isFinished = it.isFinished,
                isActive = it.isActive,
            )
        },
    )

data class VoteRequest(val movieId: Long, val choice: VoteChoice)

data class VoteStatusResponse(
    val sessionId: UUID,
    val deckSize: Int,
    val activeCount: Int,
    val finishedCount: Int,
    // Kotlin generates an `isComplete()` getter for this Boolean property, and Jackson's default
    // bean-property introspection strips the "is" prefix from boolean getters, which would
    // otherwise serialize this field as "complete" instead of "isComplete" -- pin the wire name
    // explicitly so API consumers see the field name the DTO actually declares.
    @get:JsonProperty("isComplete")
    val isComplete: Boolean,
    val matchedMovieIds: List<Long>,
    val likeCounts: List<MovieLikeCountResponse>,
)

data class MovieLikeCountResponse(val movieId: Long, val likeCount: Int)

data class SessionRosterResponse(
    val sessionId: UUID,
    val deckSize: Int,
    val participants: List<ParticipantProgressResponse>,
)

data class ParticipantProgressResponse(
    val participantId: UUID,
    val displayName: String,
    val votedCount: Int,
    // Same fix as VoteStatusResponse.isComplete above: Kotlin generates isFinished()/isActive()
    // getters, and Jackson's bean introspection strips the leading two-letter prefix from boolean
    // getters, which would otherwise serialize these as "finished"/"active" instead of the wire
    // names the SPA's TypeScript type declares.
    @get:JsonProperty("isFinished")
    val isFinished: Boolean,
    @get:JsonProperty("isActive")
    val isActive: Boolean,
)
