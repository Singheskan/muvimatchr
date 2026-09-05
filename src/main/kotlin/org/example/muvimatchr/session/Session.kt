package org.example.muvimatchr.session

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

// D-01's single definition of "no region specified" — imported by the service, controller, and
// tests rather than each carrying its own literal "DE".
const val DEFAULT_REGION = "DE"

@Entity
@Table(name = "session")
class Session(
    @Column(name = "join_code", nullable = false, length = 16)
    val joinCode: String,

    // Both var, not val: D-02 makes region and providerIds editable after creation by any
    // participant, unlike joinCode which is fixed at creation time.
    @Column(name = "region", nullable = false, length = 2)
    var region: String = DEFAULT_REGION,

    @Convert(converter = IntListConverter::class)
    @Column(name = "provider_ids", nullable = false, length = 255)
    var providerIds: List<Int> = emptyList(),

    // D-03: genre becomes a session-level, locked-at-pin-time field rather than a per-request
    // query parameter. Null means "no genre filter was set at pin time" -- the same
    // nullable-optional shape CatalogReferenceService.requireKnownGenre(genreId: Int?) expects.
    @Column(name = "genre")
    var genre: Int? = null,

    // Mirrors DeckCacheEntry.kt's exact JSONB pattern (same Hibernate JdbcTypeCode, same
    // ObjectMapper serialization) -- the single point-in-time snapshot of the pinned deck's full
    // movie list (D-01), not just an id list, so a later TMDB cache TTL refresh can never change
    // what a mid-session participant sees.
    @Column(name = "pinned_deck", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    var pinnedDeckJson: String? = null,

    // Null is the "not yet pinned" sentinel (D-04's lazy trigger). Written once, at pin time.
    @Column(name = "deck_pinned_at")
    var deckPinnedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null
}
