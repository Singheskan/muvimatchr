package org.example.muvimatchr.catalog

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface GenreRepository : JpaRepository<Genre, UUID> {

    fun findByTmdbId(tmdbId: Int): Genre?

    fun existsByTmdbId(tmdbId: Int): Boolean

    // Newest fetchedAt across the whole table — the freshness check for the single genre scope
    // (there is only one, unlike watch_provider which is region-scoped).
    @Query("SELECT MAX(g.fetchedAt) FROM Genre g")
    fun findNewestFetchedAt(): Instant?

    // Single native upsert, copied structurally from VoteRepository.upsertVote /
    // DeckCacheRepository.upsertDeck — this single statement, not a lookup followed by a
    // conditional insert, is what makes two simultaneous refreshes of the genre scope converge on
    // one row per tmdb_id rather than racing into a duplicate row or
    // DataIntegrityViolationException.
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO genre (id, tmdb_id, name, fetched_at)
            VALUES (:id, :tmdbId, :name, now())
            ON CONFLICT (tmdb_id)
            DO UPDATE SET name = EXCLUDED.name, fetched_at = now()
        """,
        nativeQuery = true,
    )
    fun upsertGenre(
        @Param("id") id: UUID,
        @Param("tmdbId") tmdbId: Int,
        @Param("name") name: String,
    )
}
