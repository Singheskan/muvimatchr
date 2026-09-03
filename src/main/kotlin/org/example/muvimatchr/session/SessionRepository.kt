package org.example.muvimatchr.session

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SessionRepository : JpaRepository<Session, UUID> {
    fun findByJoinCode(joinCode: String): Session?
}
