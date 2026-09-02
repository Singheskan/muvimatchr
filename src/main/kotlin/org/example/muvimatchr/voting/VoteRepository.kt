package org.example.muvimatchr.voting

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface VoteRepository : JpaRepository<Vote, UUID> {

    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO vote (id, session_id, participant_id, movie_id, choice, voted_at)
            VALUES (:id, CAST(:sessionId AS uuid), CAST(:participantId AS uuid), :movieId, :choice, now())
            ON CONFLICT (session_id, participant_id, movie_id)
            DO UPDATE SET choice = EXCLUDED.choice, voted_at = now()
        """,
        nativeQuery = true,
    )
    fun upsertVote(
        @Param("id") id: UUID,
        @Param("sessionId") sessionId: UUID,
        @Param("participantId") participantId: UUID,
        @Param("movieId") movieId: Long,
        @Param("choice") choice: String,
    )
}
