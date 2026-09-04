package org.example.muvimatchr.catalog

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface DeckCacheRepository : JpaRepository<DeckCacheEntry, UUID> {

    fun findByCacheKey(cacheKey: String): DeckCacheEntry?

    // Single native upsert, copied structurally from VoteRepository.upsertVote — this single
    // statement, not a lookup followed by a conditional insert, is what makes two simultaneous
    // refreshes of the same cache key converge on one row (RESEARCH.md Pitfall 3). The id
    // argument is only consumed on the insert path; the conflict path keeps the existing row's id.
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO deck_cache_entry (id, cache_key, movies, total_results, fetched_at)
            VALUES (:id, :cacheKey, CAST(:movies AS jsonb), :totalResults, now())
            ON CONFLICT (cache_key)
            DO UPDATE SET movies = EXCLUDED.movies, total_results = EXCLUDED.total_results, fetched_at = now()
        """,
        nativeQuery = true,
    )
    fun upsertDeck(
        @Param("id") id: UUID,
        @Param("cacheKey") cacheKey: String,
        @Param("movies") movies: String,
        @Param("totalResults") totalResults: Int,
    )
}
