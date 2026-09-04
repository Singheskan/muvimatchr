package org.example.muvimatchr.session

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
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

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null
}
