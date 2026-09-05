package org.example.muvimatchr.voting

import org.example.muvimatchr.session.SessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class VoteService(
    private val sessionRepository: SessionRepository,
    private val voteRepository: VoteRepository,
    private val matchAggregationService: MatchAggregationService,
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
        return matchAggregationService.computeStatus(sessionId)
    }
}
