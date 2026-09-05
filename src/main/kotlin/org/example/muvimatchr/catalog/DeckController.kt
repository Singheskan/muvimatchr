package org.example.muvimatchr.catalog

import org.example.muvimatchr.auth.CurrentParticipant
import org.example.muvimatchr.session.Participant
import org.example.muvimatchr.session.SessionRepository
import org.example.muvimatchr.session.SessionService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/sessions")
class DeckController(
    private val movieCatalogService: MovieCatalogService,
    private val sessionRepository: SessionRepository,
    private val sessionService: SessionService,
) {

    // Requiring @CurrentParticipant is deliberate and is this endpoint's access control:
    // without it the endpoint would be an unauthenticated relay that any anonymous caller
    // could use to burn the application's TMDB rate budget. This endpoint accepts no other
    // request parameters at all -- region, providerIds and genre are all session-sourced (below);
    // an id that never enters through this endpoint cannot be validated here, which is why
    // requireKnownGenre lives at the two endpoints that do accept one (SessionController).
    @GetMapping("/{sessionId}/deck")
    fun getDeck(
        @PathVariable sessionId: UUID,
        @CurrentParticipant participant: Participant,
    ): DeckResponse {
        if (participant.session.id != sessionId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
        }
        val session = sessionRepository.findById(sessionId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No such session")
        }

        // D-01/D-04: once a deck has been pinned for this session, that snapshot is the sole
        // source of truth from here on. No further upstream catalog request is made.
        if (session.deckPinnedAt != null) {
            val pinnedMovies = sessionService.pinnedMovies(session)
            return DeckResponse(
                sessionId = sessionId,
                status = "ok",
                stale = false,
                fetchedAt = session.deckPinnedAt!!,
                totalResults = pinnedMovies.size,
                movies = pinnedMovies.map { it.toDeckMovieResponse() },
            )
        }

        // Genre, region and provider selection all come only from the session row -- never from a
        // request parameter. This is what makes the filter set a group-level decision: two
        // participants of the same session always get the same filtering, because there is no
        // per-client value for any of the three to disagree about. Do not add a convenience
        // query-bound parameter for any of them; that would quietly reintroduce per-client
        // divergence this endpoint is built to prevent. Genre was validated (requireKnownGenre) at
        // the two endpoints that set it -- SessionController.createSession/replaceFilters -- before
        // it was ever written here, so no further validation happens on this read path.
        val genreId = session.genre
        val result = movieCatalogService.getDeck(genreId, session.providerIds, session.region)

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
            // D-06: a result below MINIMUM_DECK_SIZE must leave deck_pinned_at null so the group
            // can still widen its filters (04-RESEARCH.md Pitfall D) -- pinDeck is called only on
            // the branch below that returns status "ok".
            sessionService.pinDeck(sessionId, genreId, result.movies)
            DeckResponse(
                sessionId = sessionId,
                status = "ok",
                stale = result.stale,
                fetchedAt = result.fetchedAt,
                totalResults = result.totalResults,
                movies = result.movies.map { it.toDeckMovieResponse() },
            )
        }
    }
}

private fun MovieCatalogService.CachedMovie.toDeckMovieResponse(): DeckMovieResponse =
    DeckMovieResponse(
        tmdbId = tmdbId,
        title = title,
        posterPath = posterPath,
        genreIds = genreIds,
        voteAverage = voteAverage,
        releaseDate = releaseDate,
        overview = overview,
        providers = providers.map { DeckProviderResponse(it.providerId, it.providerName, it.logoPath) },
        watchLink = watchLink,
    )

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
