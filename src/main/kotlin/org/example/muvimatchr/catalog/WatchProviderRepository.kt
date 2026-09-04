package org.example.muvimatchr.catalog

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface WatchProviderRepository : JpaRepository<WatchProvider, UUID> {

    fun findByRegionOrderByDisplayPriorityAsc(region: String): List<WatchProvider>

    fun existsByRegionAndTmdbId(region: String, tmdbId: Int): Boolean

    // Newest fetchedAt scoped to one region — each region refreshes independently.
    @Query("SELECT MAX(w.fetchedAt) FROM WatchProvider w WHERE w.region = :region")
    fun findNewestFetchedAtForRegion(@Param("region") region: String): Instant?

    // Single native upsert conflicting on the composite (region, tmdb_id) — same atomic-write
    // discipline as GenreRepository.upsertGenre/DeckCacheRepository.upsertDeck, required because
    // the same provider id legitimately appears in multiple regions.
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO watch_provider (id, tmdb_id, region, name, logo_path, display_priority, fetched_at)
            VALUES (:id, :tmdbId, :region, :name, :logoPath, :displayPriority, now())
            ON CONFLICT (region, tmdb_id)
            DO UPDATE SET name = EXCLUDED.name, logo_path = EXCLUDED.logo_path,
                display_priority = EXCLUDED.display_priority, fetched_at = now()
        """,
        nativeQuery = true,
    )
    fun upsertWatchProvider(
        @Param("id") id: UUID,
        @Param("tmdbId") tmdbId: Int,
        @Param("region") region: String,
        @Param("name") name: String,
        @Param("logoPath") logoPath: String?,
        @Param("displayPriority") displayPriority: Int?,
    )
}
