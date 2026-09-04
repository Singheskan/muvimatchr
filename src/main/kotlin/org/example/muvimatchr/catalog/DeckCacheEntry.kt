package org.example.muvimatchr.catalog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

// Plain class, following Session.kt's JPA entity convention exactly. No @Table(uniqueConstraints
// = ...) here — the named index in V5__create_deck_cache_entry.sql is the sole source of truth
// for single-column uniqueness on cache_key, matching the precedent set by the participant token
// hash. The cache is filter-scoped and shared across sessions per D-07 — deliberately no session
// or participant column.
@Entity
@Table(name = "deck_cache_entry")
class DeckCacheEntry(
    @Column(name = "cache_key", nullable = false, length = 128)
    val cacheKey: String,

    @Column(name = "movies", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    var moviesJson: String,

    @Column(name = "total_results", nullable = false)
    var totalResults: Int,

    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null
}
