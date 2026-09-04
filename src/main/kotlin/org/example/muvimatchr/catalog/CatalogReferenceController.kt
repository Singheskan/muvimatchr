package org.example.muvimatchr.catalog

import org.example.muvimatchr.auth.CurrentParticipant
import org.example.muvimatchr.session.Participant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/catalog")
class CatalogReferenceController(private val catalogReferenceService: CatalogReferenceService) {

    // @CurrentParticipant here is authentication-only, deliberately not compared against any
    // session: this reference data is not session-scoped, but requiring a valid token keeps these
    // endpoints from being an unauthenticated relay of the application's TMDB budget, the same
    // reasoning that guards the deck endpoint.
    @GetMapping("/genres")
    fun genres(@CurrentParticipant participant: Participant): List<GenreResponse> =
        catalogReferenceService.genres().map { GenreResponse(it.tmdbId, it.name) }

    @GetMapping("/watch-providers")
    fun watchProviders(@RequestParam region: String, @CurrentParticipant participant: Participant): List<WatchProviderResponse> =
        catalogReferenceService.watchProviders(region).map {
            WatchProviderResponse(it.tmdbId, it.name, it.logoPath, it.displayPriority)
        }
}

// tmdbId is mapped onto id so callers see TMDB's identifier, which is the one they pass back as a
// filter value -- the internal row UUID is never placed in either response.
data class GenreResponse(val id: Int, val name: String)

data class WatchProviderResponse(val id: Int, val name: String, val logoPath: String?, val displayPriority: Int?)
