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

    // D-06: "active" is last vote -- or join time, if the participant has cast none yet -- within
    // timeoutSeconds. That COALESCE is D-06 stated in one expression. Recomputed fresh on every
    // call, per D-07: nothing here is cached or sticky.
    @Query(
        value = """
            SELECT p.id
            FROM participant p
            LEFT JOIN LATERAL (
                SELECT MAX(v.voted_at) AS last_voted_at
                FROM vote v
                WHERE v.participant_id = p.id AND v.session_id = CAST(:sessionId AS uuid)
            ) lv ON true
            WHERE p.session_id = CAST(:sessionId AS uuid)
              AND COALESCE(lv.last_voted_at, p.created_at) > now() - (CAST(:timeoutSeconds AS int) * INTERVAL '1 second')
            ORDER BY p.id
        """,
        nativeQuery = true,
    )
    fun findActiveParticipantIds(
        @Param("sessionId") sessionId: UUID,
        @Param("timeoutSeconds") timeoutSeconds: Int,
    ): List<UUID>

    // Counts, among the given (already-active) participant ids, how many have cast at least
    // deckSize votes in this session -- i.e. have finished the pinned deck.
    @Query(
        value = """
            SELECT count(*) FROM (
                SELECT v.participant_id
                FROM vote v
                WHERE v.session_id = CAST(:sessionId AS uuid) AND v.participant_id IN (:participantIds)
                GROUP BY v.participant_id
                HAVING COUNT(*) >= :deckSize
            ) finished
        """,
        nativeQuery = true,
    )
    fun countFinishedParticipants(
        @Param("sessionId") sessionId: UUID,
        @Param("participantIds") participantIds: Collection<UUID>,
        @Param("deckSize") deckSize: Int,
    ): Int

    // VOTE-04: a movie is unanimous when every one of the given (already-active) participants
    // liked it -- the HAVING equality against the full active count, not a partial-agreement
    // threshold, is the entire rule. ORDER BY is established here, in the database, so the result
    // is stable across calls without any re-sequencing in Kotlin afterwards.
    @Query(
        value = """
            SELECT v.movie_id
            FROM vote v
            WHERE v.session_id = CAST(:sessionId AS uuid)
              AND v.choice = :likeChoice
              AND v.participant_id IN (:participantIds)
            GROUP BY v.movie_id
            HAVING COUNT(DISTINCT v.participant_id) = :participantCount
            ORDER BY v.movie_id
        """,
        nativeQuery = true,
    )
    fun findUnanimousMovieIds(
        @Param("sessionId") sessionId: UUID,
        @Param("participantIds") participantIds: Collection<UUID>,
        @Param("participantCount") participantCount: Int,
        @Param("likeChoice") likeChoice: String,
    ): List<Long>

    // RSLT-03: per-movie like counts, deliberately unfiltered by active roster or unanimity -- an
    // idle participant's earlier likes still count toward every movie's tally (D-07). Two ordering
    // keys, not one: a single count key would leave tied movies in an unspecified order that can
    // differ between calls.
    @Query(
        value = """
            SELECT v.movie_id, COUNT(*)
            FROM vote v
            WHERE v.session_id = CAST(:sessionId AS uuid) AND v.choice = :likeChoice
            GROUP BY v.movie_id
            ORDER BY COUNT(*) DESC, v.movie_id ASC
        """,
        nativeQuery = true,
    )
    fun findLikeCountsBySession(
        @Param("sessionId") sessionId: UUID,
        @Param("likeChoice") likeChoice: String,
    ): List<Array<Any>>
}
