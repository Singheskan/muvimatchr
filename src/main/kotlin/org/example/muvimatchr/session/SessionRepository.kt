package org.example.muvimatchr.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SessionRepository : JpaRepository<Session, UUID> {
    fun findByJoinCode(joinCode: String): Session?

    // Spring Data JPA's @Lock annotation is silently ignored on native queries (04-RESEARCH.md
    // Pitfall A) -- the row-lock clause must be written directly into the SQL text itself, as
    // here. No @Modifying: this is a read, not a write. The lock is only held for as long as the
    // caller's enclosing transaction stays open (Pitfall B) -- callers must invoke this as the
    // first statement inside their own @Transactional method.
    @Query(value = "SELECT id FROM session WHERE id = CAST(:sessionId AS uuid) FOR UPDATE", nativeQuery = true)
    fun lockForUpdate(@Param("sessionId") sessionId: UUID): UUID?
}
