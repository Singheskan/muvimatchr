package org.example.muvimatchr.session

import org.example.muvimatchr.auth.CurrentParticipant
import org.example.muvimatchr.voting.VoteRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

// Backend half of the SPA's join-code-to-session resolution (D-04). Deliberately requires the
// bearer token: an anonymous join-code-to-session-UUID lookup would hand any holder of a
// six-character code a session UUID that is subscribable on the unauthenticated STOMP topic
// (threat T-06-02).
@RestController
@RequestMapping("/api/sessions")
class SessionBootstrapController(private val voteRepository: VoteRepository) {

    @GetMapping("/by-code/{joinCode}/me")
    fun me(
        @PathVariable joinCode: String,
        @CurrentParticipant participant: Participant,
    ): SessionBootstrapResponse {
        // This equality check is the entire authorisation rule -- it is what stops a token for one
        // session resolving another (threat T-06-02).
        if (participant.session.joinCode != joinCode) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
        }
        val sessionId = participant.session.id!!
        val participantId = participant.id!!
        val votedMovieIds = voteRepository.findMovieIdsVotedBy(sessionId, participantId)
        return SessionBootstrapResponse(
            sessionId = sessionId,
            joinCode = participant.session.joinCode,
            participantId = participantId,
            displayName = participant.displayName,
            deckPinned = participant.session.deckPinnedAt != null,
            votedMovieIds = votedMovieIds,
        )
    }
}

data class SessionBootstrapResponse(
    val sessionId: UUID,
    val joinCode: String,
    val participantId: UUID,
    val displayName: String,
    val deckPinned: Boolean,
    val votedMovieIds: List<Long>,
)
