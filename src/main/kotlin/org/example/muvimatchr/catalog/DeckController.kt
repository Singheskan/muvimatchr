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
        val session = sessionRepository.findById(sessionId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No such session")
        }

        // Ordering matters: validating before the outbound call is what stops an arbitrary
        // integer from ever reaching the third-party TMDB URL. requireKnownGenre no-ops when
        // genre is null.
        catalogReferenceService.requireKnownGenre(genre)

        // Region and provider selection come only from the session row -- never from a request
        // parameter. This is what makes the filter a group-level decision: two participants of
        // the same session always get the same filtering, because there is no per-client value
        // for either field to disagree about. Do not add a convenience @RequestParam for either
        // one; that would quietly reintroduce per-client divergence this endpoint is built to
        // prevent.
        val result = movieCatalogService.getDeck(genre, session.providerIds, session.region)

        // D-06: the sparse determination is made here, when shaping the response, not inside
        // MovieCatalogService.getDeck's refresh/cache logic above -- that call already read and
        // (on a cold refresh) wrote the cache normally for this exact filter combination, so a
        // sparse combination is cached under the same single rule as every other one, and a
        // repeated request for it inside the TTL costs zero further upstream calls. totalResults
        // carries the actual resolved count either way, so a caller can tell "none at all" from
        // "nearly enough" -- do not substitute, widen or backfill from an unfiltered query to
        // reach the threshold; that would silently show the user exactly the content their filter
        // excluded (see this plan's prohibition).
        return if (result.totalResults < MINIMUM_DECK_SIZE) {
            DeckResponse(
                sessionId = sessionId,
                status = "insufficient_results",
                stale = result.stale,
                fetchedAt = result.fetchedAt,
                totalResults = result.totalResults,
                movies = emptyList(),
            )
        } else {
            DeckResponse(
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
}

// The `status` field is a discriminator with two values in use: `ok` (a sufficient deck, movies
// populated) and `insufficient_results` (D-06 -- fewer than MINIMUM_DECK_SIZE movies matched,
// movies deliberately empty, totalResults still carries the true count).
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
