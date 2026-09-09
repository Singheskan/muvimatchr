package org.example.muvimatchr.catalog

import kotlinx.coroutines.reactor.awaitSingle
import org.example.muvimatchr.catalog.tmdb.TmdbDiscoverResponse
import org.example.muvimatchr.catalog.tmdb.TmdbGenreListResponse
import org.example.muvimatchr.catalog.tmdb.TmdbMovieWatchProvidersResponse
import org.example.muvimatchr.catalog.tmdb.TmdbWatchProviderListResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration

// Attempt count and delays are intentionally tunable — declared as named constants here
// rather than inline magic numbers.
private const val RETRY_MAX_ATTEMPTS = 3L
private val RETRY_MIN_BACKOFF: Duration = Duration.ofMillis(500)
private val RETRY_MAX_BACKOFF: Duration = Duration.ofSeconds(5)
private const val RETRY_JITTER = 0.5

// Bounds the cause-chain walk in hasIOExceptionCause() -- generous enough for WebClient's
// observed one level of wrapping (WebClientRequestException -> ConnectException) plus headroom
// for Reactor Netty's own occasional double-wrap, without risking an unbounded walk on a
// pathological or cyclic cause chain.
private const val CAUSE_CHAIN_SEARCH_DEPTH = 8

@Component
class MovieCatalogClient(private val tmdbWebClient: WebClient) {

    // D-05: one fixed page per filter combination, no pagination parameter. D-03/PROJECT.md:
    // sort_by=popularity.desc is the entirety of the deck's ordering logic, always present.
    // Never send the monetization-type scoping parameter (COVERAGE.md OPT-OUT). When providerIds
    // is non-empty, TMDB's docs pair with_watch_providers with watch_region — sending one
    // without the other is the documented anti-pattern this client avoids.
    suspend fun discoverMovies(genreId: Int?, providerIds: List<Int>, region: String?): TmdbDiscoverResponse =
        tmdbWebClient.get()
            .uri { uriBuilder ->
                uriBuilder.path("/discover/movie")
                    .queryParam("sort_by", "popularity.desc")
                    .apply { genreId?.let { queryParam("with_genres", it) } }
                    .apply {
                        if (providerIds.isNotEmpty()) {
                            queryParam("with_watch_providers", providerIds.sorted().joinToString(","))
                            region?.let { queryParam("watch_region", it) }
                        }
                    }
                    .build()
            }
            .retrieve()
            .bodyToMono(TmdbDiscoverResponse::class.java)
            .withRetry()
            .awaitSingle()

    // Reference data (CTLG-02/CTLG-03's discoverability half, D-08's second half): the genre and
    // watch-provider lists are effectively static for weeks, so CatalogReferenceService caches
    // them in their own long-TTL tables rather than re-fetching per deck request.
    suspend fun fetchGenres(): TmdbGenreListResponse =
        tmdbWebClient.get()
            .uri { uriBuilder ->
                uriBuilder.path("/genre/movie/list")
                    .queryParam("language", "en")
                    .build()
            }
            .retrieve()
            .bodyToMono(TmdbGenreListResponse::class.java)
            .withRetry()
            .awaitSingle()

    suspend fun fetchWatchProviders(region: String): TmdbWatchProviderListResponse =
        tmdbWebClient.get()
            .uri { uriBuilder ->
                uriBuilder.path("/watch/providers/movie")
                    .queryParam("watch_region", region)
                    .build()
            }
            .retrieve()
            .bodyToMono(TmdbWatchProviderListResponse::class.java)
            .withRetry()
            .awaitSingle()

    // D-10 / CTLG-04: per-movie provider resolution. This is called only from
    // MovieCatalogService's refresh branch (never per individual deck-fetch request) -- the
    // per-title endpoint returns every region's availability in one payload, so no region query
    // parameter is attached here; region selection happens when reading the response
    // (resolveRegionalAvailability), not when building the request.
    suspend fun fetchMovieWatchProviders(movieId: Long): TmdbMovieWatchProvidersResponse =
        tmdbWebClient.get()
            .uri { uriBuilder -> uriBuilder.path("/movie/{movieId}/watch/providers").build(movieId) }
            .retrieve()
            .bodyToMono(TmdbMovieWatchProvidersResponse::class.java)
            .withRetry()
            .awaitSingle()

    // The one retry policy shared by discoverMovies/fetchGenres/fetchWatchProviders/fetchMovieWatchProviders — factored
    // into a single helper so a future tuning change applies to every outbound call, not just some.
    //
    // CR-04 (live-verified fix): WebClientResponseException is only thrown when TMDB actually
    // returns an HTTP response with an error status. A genuine connectivity failure -- connection
    // refused, DNS resolution failure, TLS handshake failure, a response timeout with no bytes
    // received -- throws java.net.ConnectException, Reactor Netty's
    // PrematureCloseException/ReadTimeoutException, etc. But Spring WebClient does not surface
    // those directly: it wraps every request-phase I/O failure in WebClientRequestException (a
    // plain RuntimeException, not an IOException itself), with the real IOException only reachable
    // via `.cause`. Checking `throwable is java.io.IOException` alone therefore never matches --
    // confirmed live via a real connection-refused failure: the request failed raw as an unhandled
    // 500 in ~0.2s (no retry, no backoff) instead of retrying and falling through to the
    // stale/503 degradation ladder. Walking the cause chain (bounded, to avoid an unbounded/cyclic
    // chain) is the fix: check every throwable in the chain, not just the outermost one.
    // internal, not private: exercised directly by a focused unit test (MovieCatalogClientTest)
    // reproducing the exact WebClientRequestException-wrapping-ConnectException shape confirmed
    // live, since MockWebServer has no simple way to simulate a genuine connection-refused failure.
    internal fun Throwable.hasIOExceptionCause(): Boolean =
        generateSequence(this) { it.cause }.take(CAUSE_CHAIN_SEARCH_DEPTH).any { it is java.io.IOException }

    private fun <T : Any> Mono<T>.withRetry(): Mono<T> =
        retryWhen(
            Retry.backoff(RETRY_MAX_ATTEMPTS, RETRY_MIN_BACKOFF)
                .maxBackoff(RETRY_MAX_BACKOFF)
                .jitter(RETRY_JITTER)
                .filter { throwable ->
                    (throwable is WebClientResponseException &&
                        (throwable.statusCode.is5xxServerError || throwable.statusCode == HttpStatus.TOO_MANY_REQUESTS)) ||
                        throwable.hasIOExceptionCause()
                }
        )
}
