package org.example.muvimatchr.voting

import org.example.muvimatchr.realtime.SessionEventPublisher
import org.example.muvimatchr.session.SessionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

private val log = LoggerFactory.getLogger(VoteService::class.java)

@Service
class VoteService(
    private val sessionRepository: SessionRepository,
    private val voteRepository: VoteRepository,
    private val matchAggregationService: MatchAggregationService,
    private val sessionEventPublisher: SessionEventPublisher,
) {
    // The transaction annotation belongs on this method and nowhere else in the path: repository
    // methods called from inside it inherit this transaction, and without a single enclosing
    // boundary the row lock is released before the upsert runs (04-RESEARCH.md Pitfall B). No
    // outbound HTTP call happens inside this method -- the only catalog-calling path is the deck
    // endpoint, deliberately kept out of this locked window (T-04-05).
    @Transactional
    fun recordVote(sessionId: UUID, participantId: UUID, movieId: Long, choice: VoteChoice): SessionVoteStatus {
        // Every concurrent recordVote() call for THIS session blocks here until the previous
        // call's transaction commits/rolls back. Calls for other sessions are unaffected -- this
        // is a row lock, not a table lock (T-04-05).
        sessionRepository.lockForUpdate(sessionId)
        voteRepository.upsertVote(UUID.randomUUID(), sessionId, participantId, movieId, choice.name)
        // Runs inside the still-open, still-locked transaction: a second concurrent caller's read
        // here is guaranteed to see this caller's already-committed vote.
        val status = matchAggregationService.computeStatus(sessionId)

        // Broadcast only after this transaction commits, never inline. `status` is a fresh read
        // taken *inside* the still-open transaction; an inline push would reach subscribers
        // before the write is visible to any other connection, so a client that reacted to the
        // push by immediately fetching /api/sessions/{sessionId}/votes/status could read the
        // pre-commit state under READ COMMITTED and see a lower finishedCount than the push it
        // just received -- exactly the stale-reconciliation outcome RTIME-03 forbids. Registering
        // after commit also guarantees a rollback can never publish a status that never became
        // true, and that a broadcast problem can never roll back an already-durable vote. Do not
        // "simplify" this back to an inline call.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    sessionEventPublisher.broadcastStatus(sessionId, status)
                }
            })
        } else {
            // Cannot happen through this @Transactional method today -- Spring guarantees an
            // active synchronization inside a @Transactional method body. This branch exists only
            // to keep the method honest if it is ever invoked outside a transactional context; it
            // deliberately does NOT broadcast (broadcastStatus has exactly one call site in this
            // file, inside the afterCommit hook above, per D-05), since broadcasting here would
            // republish a status this method cannot actually guarantee has committed.
            log.warn(
                "recordVote ran without an active transaction synchronization for session {} -- " +
                    "skipping broadcast, since the committed-state guarantee cannot be made here",
                sessionId,
            )
        }

        return status
    }
}
