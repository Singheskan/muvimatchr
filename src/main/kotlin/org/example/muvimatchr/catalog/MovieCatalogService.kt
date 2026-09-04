package org.example.muvimatchr.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.example.muvimatchr.catalog.tmdb.TmdbMovie
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

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

        // The freshness check is a plain read; the write path below is a single atomic upsert
        // (DeckCacheRepository.upsertDeck), not a check-then-insert pair — that's precisely what
        // makes two concurrent refreshes of the same cache key converge on one row rather than
        // racing into a duplicate-row or DataIntegrityViolationException (RESEARCH.md Pitfall 3).
        if (existing != null && Duration.between(existing.fetchedAt, Instant.now()).toHours() < deckTtlHours) {
            val cachedMovies: List<CachedMovie> = objectMapper.readValue(existing.moviesJson, object : TypeReference<List<CachedMovie>>() {})
            return DeckResult(cachedMovies, existing.totalResults, existing.fetchedAt, stale = false)
        }

        // Plan 03-05 extends this refresh path with the failure fallback (D-04) and the
        // sparse-result branch (D-06); this task's refresh path lets an exhausted-retry
        // exception from the discover call itself propagate.
        val (movies, totalResults) = runBlocking {
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
        val json = objectMapper.writeValueAsString(movies)
        val fetchedAt = Instant.now()
        // id is only consumed on the insert path of the ON CONFLICT upsert; the conflict path
        // keeps the existing row's id.
        deckCacheRepository.upsertDeck(UUID.randomUUID(), key, json, totalResults)
        return DeckResult(movies, totalResults, fetchedAt, stale = false)
    }

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
