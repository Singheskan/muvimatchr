package org.example.muvimatchr.catalog

import kotlinx.coroutines.runBlocking
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
        // exception propagate.
        val response = runBlocking { movieCatalogClient.discoverMovies(genreId, providerIds, region) }
        val movies = response.results.map { it.toCachedMovie() }
        val json = objectMapper.writeValueAsString(movies)
        val fetchedAt = Instant.now()
        // id is only consumed on the insert path of the ON CONFLICT upsert; the conflict path
        // keeps the existing row's id.
        deckCacheRepository.upsertDeck(UUID.randomUUID(), key, json, response.totalResults)
        return DeckResult(movies, response.totalResults, fetchedAt, stale = false)
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
