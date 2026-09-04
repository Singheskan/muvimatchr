package org.example.muvimatchr.catalog

import jakarta.validation.constraints.Pattern
import org.example.muvimatchr.auth.CurrentParticipant
import org.example.muvimatchr.session.Participant
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/catalog")
@Validated
class CatalogReferenceController(private val catalogReferenceService: CatalogReferenceService) {

    // @CurrentParticipant here is authentication-only, deliberately not compared against any
    // session: this reference data is not session-scoped, but requiring a valid token keeps these
    // endpoints from being an unauthenticated relay of the application's TMDB budget, the same
    // reasoning that guards the deck endpoint.
    @GetMapping("/genres")
    fun genres(@CurrentParticipant participant: Participant): List<GenreResponse> =
        catalogReferenceService.genres().map { GenreResponse(it.tmdbId, it.name) }

    // CR-03: every sibling region-accepting endpoint (SessionController's Create/FiltersRequest)
    // constrains region to ^[A-Z]{2}$ before it reaches the service layer. This endpoint took a
    // plain, unvalidated String, letting an arbitrary-length region flow through to TMDB
    // (watch_region) and, on a matching TMDB response, into a `region VARCHAR(2)` column --
    // producing an unhandled 500 either way instead of a clean 400. Applying the same constraint
    // here (with class-level @Validated, required for method-parameter constraints on a
    // @RestController) closes that gap.
    @GetMapping("/watch-providers")
    fun watchProviders(
        @RequestParam @Pattern(regexp = "^[A-Z]{2}$") region: String,
        @CurrentParticipant participant: Participant,
    ): List<WatchProviderResponse> =
        catalogReferenceService.watchProviders(region).map {
            WatchProviderResponse(it.tmdbId, it.name, it.logoPath, it.displayPriority)
        }
}

// tmdbId is mapped onto id so callers see TMDB's identifier, which is the one they pass back as a
// filter value -- the internal row UUID is never placed in either response.
data class GenreResponse(val id: Int, val name: String)

data class WatchProviderResponse(val id: Int, val name: String, val logoPath: String?, val displayPriority: Int?)
