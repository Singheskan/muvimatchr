package org.example.muvimatchr.session

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ParticipantRepository : JpaRepository<Participant, UUID> {
    fun findByTokenHash(tokenHash: String): Participant?

    // The roster's display order comes from the database, not from a Kotlin re-sort -- callers
    // must never re-order the returned list.
    fun findBySession_IdOrderByCreatedAtAsc(sessionId: UUID): List<Participant>
}
