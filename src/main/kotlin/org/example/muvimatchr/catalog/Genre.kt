package org.example.muvimatchr.catalog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// Plain class, following DeckCacheEntry/Session's JPA entity convention exactly. The named
// uq_genre_tmdb_id index in V7__create_catalog_reference_tables.sql is the sole source of truth
// for uniqueness on tmdb_id.
@Entity
@Table(name = "genre")
class Genre(
    @Column(name = "tmdb_id", nullable = false)
    val tmdbId: Int,

    @Column(name = "name", nullable = false, length = 64)
    var name: String,

    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null
}
