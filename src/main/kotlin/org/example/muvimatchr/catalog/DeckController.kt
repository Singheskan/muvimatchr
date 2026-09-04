package org.example.muvimatchr.catalog

import org.example.muvimatchr.auth.CurrentParticipant
import org.example.muvimatchr.session.Participant
import org.example.muvimatchr.session.SessionRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/sessions")
class DeckController(
    private val movieCatalogService: MovieCatalogService,
    private val sessionRepository: SessionRepository,
    private val catalogReferenceService: CatalogReferenceService,
) {

    // Requiring @CurrentParticipant is deliberate and is this endpoint's access control:
    // without it the endpoint would be an unauthenticated relay that any anonymous caller
    // could use to burn the application's TMDB rate budget.
    @GetMapping("/{sessionId}/deck")
    fun getDeck(
        @PathVariable sessionId: UUID,
        @RequestParam(required = false) genre: Int?,
        @CurrentParticipant participant: Participant,
    ): DeckResponse {
        if (participant.session.id != sessionId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
        }
        sessionRepository.findById(sessionId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No such session")
        }

        // Ordering matters: validating before the outbound call is what stops an arbitrary
        // integer from ever reaching the third-party TMDB URL. requireKnownGenre no-ops when
        // genre is null.
        catalogReferenceService.requireKnownGenre(genre)

        // This plan sources no provider or region; Plan 03-04 replaces those two arguments with
        // the session's stored selection.
        val result = movieCatalogService.getDeck(genre, emptyList(), null)

        return DeckResponse(
            sessionId = sessionId,
            status = "ok",
            stale = result.stale,
            fetchedAt = result.fetchedAt,
            totalResults = result.totalResults,
            movies = result.movies.map { movie ->
                DeckMovieResponse(
                    tmdbId = movie.tmdbId,
                    title = movie.title,
                    posterPath = movie.posterPath,
                    genreIds = movie.genreIds,
                    voteAverage = movie.voteAverage,
                    releaseDate = movie.releaseDate,
                    overview = movie.overview,
                    providers = movie.providers.map { DeckProviderResponse(it.providerId, it.providerName, it.logoPath) },
                    watchLink = movie.watchLink,
                )
            },
        )
    }
}

// The `status` field is a discriminator whose other value, `insufficient_results`, Plan 03-05
// introduces for D-06; declaring it now keeps Phase 6's branching contract stable.
data class DeckResponse(
    val sessionId: UUID,
    val status: String,
    val stale: Boolean,
    val fetchedAt: Instant,
    val totalResults: Int,
    val movies: List<DeckMovieResponse>,
)

data class DeckMovieResponse(
    val tmdbId: Long,
    val title: String,
    val posterPath: String?,
    val genreIds: List<Int>,
    val voteAverage: Double,
    val releaseDate: String?,
    val overview: String?,
    val providers: List<DeckProviderResponse>,
    val watchLink: String?,
)

data class DeckProviderResponse(
    val providerId: Int,
    val providerName: String,
    val logoPath: String?,
)
