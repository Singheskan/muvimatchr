package org.example.muvimatchr.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.example.muvimatchr.catalog.tmdb.TmdbMovie
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import reactor.core.Exceptions
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

// D-06's floor: a filter combination resolving to fewer than this many movies is not returned as
// a thin deck -- DeckController branches on it to build the insufficient_results envelope instead.
// Declared once here so the service, the controller and the tests share one definition rather
// than three copies of the literal.
const val MINIMUM_DECK_SIZE = 5

@Service
class MovieCatalogService(
    private val deckCacheRepository: DeckCacheRepository,
    private val movieCatalogClient: MovieCatalogClient,
    private val objectMapper: ObjectMapper,
) {
    @Value("\${tmdb.cache.deck-ttl-hours:6}")
    private var deckTtlHours: Long = 6

    @Value("\${tmdb.provider-lookup.max-concurrency:8}")
    private var providerLookupConcurrency: Int = 8

    fun getDeck(genreId: Int?, providerIds: List<Int>, region: String?): DeckResult {
        val key = buildDeckCacheKey(genreId, providerIds, region)
        val existing = deckCacheRepository.findByCacheKey(key)

        // The freshness check is a plain read; the write path below is a single atomic upsert on
        // DeckCacheRepository, not a check-then-insert pair — that's precisely what makes two
        // concurrent refreshes of the same cache key converge on one row rather than racing into
        // a duplicate-row or DataIntegrityViolationException (RESEARCH.md Pitfall 3). It is also
        // the failure path's contract below: exactly one call site writes the cache, and it is
        // gated on success only.
        if (existing != null && Duration.between(existing.fetchedAt, Instant.now()).toHours() < deckTtlHours) {
            return DeckResult(deserializeMovies(existing), existing.totalResults, existing.fetchedAt, stale = false)
        }

        // D-04's degradation ladder: step 1 (retry with backoff) happens inside
        // MovieCatalogClient's Retry.backoff, scoped to 5xx/429 only -- a 4xx client error is
        // never retried and propagates here on its first and only attempt, uncaught by the block
        // below. This catch is step 2: reactor.core.Exceptions.isRetryExhausted(e) is true only
        // once the client's own retries are exhausted, and only then do we fall back to
        // `existing` -- the exact row already read above (not a second lookup), so the fallback
        // can never observe a different row than the freshness decision above was made on. Step 3,
        // the only case that actually fails the request, is reached only when there is no cached
        // row at all for this exact filter combination.
        val (movies, totalResults) = try {
            runBlocking {
                val discoverResponse = movieCatalogClient.discoverMovies(genreId, providerIds, region)
                val baseMovies = discoverResponse.results.map { it.toCachedMovie() }
                // D-10/T-03-22: per-movie availability resolution happens only here, on the refresh
                // path, never on the cache-hit branch above. It needs a concrete region to resolve
                // against; region is only absent when a caller supplies none (no current production
                // caller does, since DeckController always sources a session's region, which
                // defaults to DE) -- in that case there is nothing to resolve, so movies keep their
                // empty provider list/null watch link rather than resolving against an arbitrary one.
                val resolvedMovies = if (region != null) resolveAvailability(baseMovies, region) else baseMovies
                resolvedMovies to discoverResponse.totalResults
            }
        } catch (e: Exception) {
            if (Exceptions.isRetryExhausted(e)) {
                // stale=true is not cosmetic here: it is the only signal that lets a caller tell a
                // degraded, past-TTL deck apart from one that was just freshly (re)fetched.
                if (existing != null) {
                    return DeckResult(deserializeMovies(existing), existing.totalResults, existing.fetchedAt, stale = true)
                }
                // Nothing written here: an empty/partial row on this path would be served as a
                // legitimate cached deck by every subsequent request until the TTL expired,
                // turning a transient outage into a persistent wrong answer (T-03-28).
                throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Movie catalog is temporarily unavailable and no cached deck exists for these filters",
                )
            }
            throw e
        }
        val json = objectMapper.writeValueAsString(movies)
        val fetchedAt = Instant.now()
        // id is only consumed on the insert path of the ON CONFLICT upsert; the conflict path
        // keeps the existing row's id.
        // CR-01: this write can still fail (e.g. a cache-key collision that survives hashing, or
        // any other constraint violation) after the TMDB call has already succeeded. A caching
        // failure must not turn an otherwise-successful fetch into an unhandled 500 -- the freshly
        // fetched deck is still valid and returnable even if it couldn't be persisted.
        try {
            deckCacheRepository.upsertDeck(UUID.randomUUID(), key, json, totalResults)
        } catch (e: DataIntegrityViolationException) {
            // Deliberately swallowed: the deck below is returned to the caller regardless of
            // whether it could be cached. The next request for this filter combination simply
            // misses the cache and re-fetches from TMDB.
        }
        return DeckResult(movies, totalResults, fetchedAt, stale = false)
    }

    private fun deserializeMovies(entry: DeckCacheEntry): List<CachedMovie> =
        objectMapper.readValue(entry.moviesJson, object : TypeReference<List<CachedMovie>>() {})

    // T-03-22/T-03-26: bounded-concurrency per-movie availability resolution, folded into the
    // refresh path only -- never the cache-hit path above. A semaphore caps in-flight lookups at
    // providerLookupConcurrency (configurable, default 8 -- see application.properties for the
    // sizing rationale against TMDB's soft rate-limit ceiling). Each lookup is wrapped
    // individually: an exhausted-retry failure yields an empty provider list and null watch link
    // for that movie only (T-03-26) -- one unavailable title must not cost the user the entire
    // deck, and an empty result is an honest "we could not establish where this is streaming"
    // rather than a false availability claim.
    private suspend fun resolveAvailability(movies: List<CachedMovie>, region: String): List<CachedMovie> = coroutineScope {
        val semaphore = Semaphore(providerLookupConcurrency)
        movies.map { movie ->
            async {
                semaphore.withPermit {
                    val availability = try {
                        resolveRegionalAvailability(movieCatalogClient.fetchMovieWatchProviders(movie.tmdbId), region)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        RegionalAvailability(emptyList(), null)
                    }
                    movie.copy(providers = availability.providers, watchLink = availability.watchLink)
                }
            }
        }.map { it.await() }
    }

    private fun TmdbMovie.toCachedMovie(): CachedMovie =
        CachedMovie(
            tmdbId = id,
            title = title,
            posterPath = posterPath,
            genreIds = genreIds,
            voteAverage = voteAverage,
            releaseDate = releaseDate,
            overview = overview,
        )

    data class CachedMovie(
        val tmdbId: Long,
        val title: String,
        val posterPath: String?,
        val genreIds: List<Int>,
        val voteAverage: Double,
        val releaseDate: String?,
        val overview: String?,
        val providers: List<CachedProvider> = emptyList(),
        val watchLink: String? = null,
    )

    data class CachedProvider(
        val providerId: Int,
        val providerName: String,
        val logoPath: String?,
    )

    data class DeckResult(
        val movies: List<CachedMovie>,
        val totalResults: Int,
        val fetchedAt: Instant,
        val stale: Boolean,
    )
}
