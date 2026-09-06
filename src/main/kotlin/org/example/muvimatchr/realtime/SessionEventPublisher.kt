package org.example.muvimatchr.realtime

import org.example.muvimatchr.voting.SessionVoteStatus
import org.example.muvimatchr.voting.toResponse
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import java.util.UUID

// A dumb pipe from the voting layer to the broker -- no repository, no aggregation, no state.
// This class must never own business state or be queried for vote data (ARCHITECTURE.md); the
// only value it is allowed to publish is a status VoteService already computed and is about to
// return anyway.
@Service
class SessionEventPublisher(
    private val messagingTemplate: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(SessionEventPublisher::class.java)

    // Publishes status.toResponse() -- not the raw SessionVoteStatus -- because that is load
    // bearing, not cosmetic: Kotlin generates an isComplete() getter for the Boolean property,
    // and Jackson's bean introspection strips the "is" prefix from boolean getters, so a raw
    // domain object would serialize the completion flag as "complete" while the REST endpoint
    // (VoteController.getStatus) emits "isComplete". A reconnecting client reconciling a
    // remembered push against a REST fetch would then compare two different field names -- the
    // exact stale-state failure RTIME-03 forbids.
    //
    // A broadcast is best-effort UX polish, never correctness-load-bearing (ARCHITECTURE.md
    // Anti-Pattern 2): no exception may escape this method and no failure here may ever surface
    // as a failed vote request.
    fun broadcastStatus(sessionId: UUID, status: SessionVoteStatus) {
        try {
            messagingTemplate.convertAndSend("/topic/session/$sessionId", status.toResponse())
        } catch (e: Exception) {
            log.warn("Failed to broadcast vote status for session {}", sessionId, e)
        }
    }
}
