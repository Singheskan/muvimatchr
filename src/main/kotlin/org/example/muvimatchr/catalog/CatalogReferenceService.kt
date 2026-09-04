package org.example.muvimatchr.catalog

import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class CatalogReferenceService(
    private val genreRepository: GenreRepository,
    private val watchProviderRepository: WatchProviderRepository,
    private val movieCatalogClient: MovieCatalogClient,
) {
    @Value("\${tmdb.cache.reference-ttl-hours:168}")
    private var referenceTtlHours: Long = 168

    // Lazy-on-miss, not a scheduled job: CONTEXT.md leaves the refresh mechanism open, and this
    // needs no scheduler dependency and no startup ordering. The genre scope is populated the
    // first time it's read (or once it's aged past referenceTtlHours) and reused by every read
    // until then.
    fun genres(): List<Genre> {
        ensureGenresFresh()
        return genreRepository.findAll()
    }

    fun watchProviders(region: String): List<WatchProvider> {
        ensureWatchProvidersFresh(region)
        return watchProviderRepository.findByRegionOrderByDisplayPriorityAsc(region)
    }

    fun requireKnownGenre(genreId: Int?) {
        if (genreId == null) return
        ensureGenresFresh()
        if (!genreRepository.existsByTmdbId(genreId)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown genre id $genreId")
        }
    }

    fun requireKnownProviders(providerIds: List<Int>, region: String) {
        if (providerIds.isEmpty()) return
        ensureWatchProvidersFresh(region)
        val unknown = providerIds.firstOrNull { !watchProviderRepository.existsByRegionAndTmdbId(region, it) }
        if (unknown != null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider id $unknown for region $region")
        }
    }

    // WR-01: mirror MovieCatalogService.getDeck's "serve stale data on outage" design. Previously
    // this call had no try/catch: a TMDB blip while existing rows were merely stale (not absent)
    // would propagate uncaught out of genres()/requireKnownGenre(), even though perfectly
    // serviceable cached reference data was one line away. Only rethrow when there is nothing to
    // fall back to -- an empty table on a first-ever request still needs the real failure to
    // surface.
    private fun ensureGenresFresh() {
        val newest = genreRepository.findNewestFetchedAt()
        if (newest == null || isStale(newest)) {
            try {
                val response = runBlocking { movieCatalogClient.fetchGenres() }
                // Per-row upsert, not a bulk replace: this is what makes two simultaneous refreshes of
                // the genre scope converge on one row per tmdb_id instead of racing into a duplicate
                // row or a DataIntegrityViolationException (same reasoning as DeckCacheRepository's
                // upsertDeck).
                response.genres.forEach { genreRepository.upsertGenre(UUID.randomUUID(), it.id, it.name) }
            } catch (e: Exception) {
                if (newest == null) throw e // nothing to fall back to
                // otherwise: serve the stale rows already in the table
            }
        }
    }

    private fun ensureWatchProvidersFresh(region: String) {
        val newest = watchProviderRepository.findNewestFetchedAtForRegion(region)
        if (newest == null || isStale(newest)) {
            try {
                val response = runBlocking { movieCatalogClient.fetchWatchProviders(region) }
                response.results.forEach {
                    watchProviderRepository.upsertWatchProvider(
                        UUID.randomUUID(),
                        it.providerId,
                        region,
                        it.providerName,
                        it.logoPath,
                        it.displayPriority,
                    )
                }
            } catch (e: Exception) {
                if (newest == null) throw e // nothing to fall back to
                // otherwise: serve the stale rows already in the table
            }
        }
    }

    private fun isStale(fetchedAt: Instant): Boolean =
        Duration.between(fetchedAt, Instant.now()).toHours() >= referenceTtlHours

    // Refreshing never deletes rows the upstream response no longer contains. This is deliberate,
    // not an omission: a session's stored provider selection may reference a retired provider, and
    // removing the row would turn an existing valid selection into a validation failure on the
    // next request.
}
