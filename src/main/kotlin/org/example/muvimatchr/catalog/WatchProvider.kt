package org.example.muvimatchr.catalog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// Plain class, following Genre/DeckCacheEntry's JPA entity convention exactly. Uniqueness is the
// composite (region, tmdb_id) — uq_watch_provider_region_tmdb_id in
// V7__create_catalog_reference_tables.sql — because TMDB's provider catalogue is genuinely
// region-scoped: the same provider id legitimately appears in several regions with different
// display priorities.
@Entity
@Table(name = "watch_provider")
class WatchProvider(
    @Column(name = "tmdb_id", nullable = false)
    val tmdbId: Int,

    @Column(name = "region", nullable = false, length = 2)
    val region: String,

    @Column(name = "name", nullable = false, length = 128)
    var name: String,

    @Column(name = "logo_path", length = 255)
    var logoPath: String?,

    @Column(name = "display_priority")
    var displayPriority: Int?,

    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null
}
