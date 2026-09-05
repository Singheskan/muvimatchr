package org.example.muvimatchr.session

import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.example.muvimatchr.auth.CurrentParticipant
import org.example.muvimatchr.catalog.CatalogReferenceService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/sessions")
class SessionController(
    private val sessionService: SessionService,
    private val catalogReferenceService: CatalogReferenceService,
) {

    // required = false is load-bearing: Phase 2's existing tests and clients POST to this
    // endpoint with no body at all and must keep working.
    @PostMapping
    fun createSession(@Valid @RequestBody(required = false) request: CreateSessionRequest?): ResponseEntity<CreateSessionResponse> {
        val providerIds = request?.providerIds ?: emptyList()
        validateProviderIds(providerIds)
        // An unknown id must not be smuggled in at creation time either -- checked against the
        // region being established by this same request, not any prior/default region.
        catalogReferenceService.requireKnownProviders(providerIds, request?.region ?: DEFAULT_REGION)
        // D-03: genre is validated here, at the endpoint that sets it, not at deck-read time --
        // an unknown id must be refused before it is ever written to the session row.
        catalogReferenceService.requireKnownGenre(request?.genre)
        val session = sessionService.createSession(request?.region, providerIds, request?.genre)
        return ResponseEntity.created(URI.create("/api/sessions/${session.id}"))
            .body(CreateSessionResponse(session.id!!, session.joinCode, session.region, session.providerIds, session.genre))
    }

    @GetMapping("/{sessionId}/filters")
    fun getFilters(@PathVariable sessionId: UUID, @CurrentParticipant participant: Participant): SessionFiltersResponse {
        if (participant.session.id != sessionId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
        }
        val session = participant.session
        return SessionFiltersResponse(session.id!!, session.region, session.providerIds, session.genre)
    }

    // No check here beyond session membership (the guard above) — deliberately no further
    // condition distinguishing one participant from another. This is D-02 implemented literally,
    // preserving Phase 2's D-03 no-host-role decision; do not "fix" this by adding a
    // creator/owner check.
    @PutMapping("/{sessionId}/filters")
    fun replaceFilters(
        @PathVariable sessionId: UUID,
        @Valid @RequestBody request: SessionFiltersRequest,
        @CurrentParticipant participant: Participant,
    ): SessionFiltersResponse {
        if (participant.session.id != sessionId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
        }
        val providerIds = request.providerIds ?: emptyList()
        validateProviderIds(providerIds)
        // Validate against the region this request is establishing, not the session's previous
        // region -- a request that changes both region and providers must be checked against the
        // pair it is actually setting.
        catalogReferenceService.requireKnownProviders(providerIds, request.region ?: DEFAULT_REGION)
        catalogReferenceService.requireKnownGenre(request.genre)
        val session = sessionService.replaceFilters(sessionId, request.region, providerIds, request.genre)
        return SessionFiltersResponse(session.id!!, session.region, session.providerIds, session.genre)
    }

    // Jakarta Bean Validation container-element constraints (e.g. List<@Positive Int>) rely on a
    // RuntimeVisibleTypeAnnotations entry on the field's generic signature. Kotlin's data-class
    // codegen does not emit that attribute for a type annotation written on a generic type
    // argument (verified via javap — the annotation only survives in @Metadata, invisible to
    // Hibernate Validator), so a type-use @Positive here is silently never enforced. Validating
    // explicitly here is the reliable equivalent.
    private fun validateProviderIds(providerIds: List<Int>) {
        if (providerIds.any { it <= 0 }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "providerIds must all be positive")
        }
        // CR-01/CR-02: the raw id list is stored comma-joined in the `provider_ids VARCHAR(255)`
        // column (Session.kt) and folded into the deck cache key (CacheKey.kt). TMDB's real
        // per-region provider catalogues commonly carry 40-100+ entries, so an unbounded count is
        // reachable with entirely valid, catalogue-known ids. Capping the count here is a single
        // choke point that protects both downstream column widths.
        if (providerIds.size > MAX_PROVIDER_IDS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "providerIds must not exceed $MAX_PROVIDER_IDS entries")
        }
    }

    private companion object {
        private const val MAX_PROVIDER_IDS = 20
    }
}

data class CreateSessionRequest(
    @field:Pattern(regexp = "^[A-Z]{2}$")
    val region: String? = null,
    // Nullable, not `List<Int> = emptyList()`: Jackson's Kotlin module invokes a synthetic
    // defaults-aware constructor to compute a non-nullable parameter's default, and that path is
    // unreliable once a JSON body supplies a value for a later constructor parameter (genre) while
    // omitting this one -- observed directly as a 400 on `{"genre": 28}` (providerIds omitted,
    // genre supplied). A nullable type sidesteps the defaults-constructor path entirely: Jackson
    // can bind a bare `null` directly, and every call site below already normalises with `?:
    // emptyList()`.
    val providerIds: List<Int>? = null,
    val genre: Int? = null,
)

data class CreateSessionResponse(
    val sessionId: UUID,
    val joinCode: String,
    val region: String,
    val providerIds: List<Int>,
    val genre: Int?,
)

data class SessionFiltersRequest(
    @field:Pattern(regexp = "^[A-Z]{2}$")
    val region: String? = null,
    // See CreateSessionRequest.providerIds -- same Jackson-Kotlin defaults-constructor hazard.
    val providerIds: List<Int>? = null,
    val genre: Int? = null,
)

data class SessionFiltersResponse(val sessionId: UUID, val region: String, val providerIds: List<Int>, val genre: Int?)
