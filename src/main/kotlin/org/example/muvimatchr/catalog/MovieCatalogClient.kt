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

// Attempt count and delays are tunable per CONTEXT.md's Claude's-Discretion list — declared as
// named constants here rather than inline magic numbers.
private const val RETRY_MAX_ATTEMPTS = 3L
private val RETRY_MIN_BACKOFF: Duration = Duration.ofMillis(500)
private val RETRY_MAX_BACKOFF: Duration = Duration.ofSeconds(5)
private const val RETRY_JITTER = 0.5

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
    // CR-04: WebClientResponseException is only thrown when TMDB actually returns an HTTP
    // response with an error status. A genuine connectivity failure -- connection refused, DNS
    // resolution failure, TLS handshake failure, a response timeout with no bytes received --
    // throws a different exception type (java.net.ConnectException, Reactor Netty's
    // PrematureCloseException/ReadTimeoutException, etc.), all of which are java.io.IOException
    // subtypes. Without this branch, retryWhen never retries the single most common real-world
    // outage shape, and it also defeats MovieCatalogService.getDeck's degradation ladder: that
    // catch block relies on Exceptions.isRetryExhausted(e), which is only true for exceptions
    // that actually went through a retry cycle.
    private fun <T : Any> Mono<T>.withRetry(): Mono<T> =
        retryWhen(
            Retry.backoff(RETRY_MAX_ATTEMPTS, RETRY_MIN_BACKOFF)
                .maxBackoff(RETRY_MAX_BACKOFF)
                .jitter(RETRY_JITTER)
                .filter { throwable ->
                    (throwable is WebClientResponseException &&
                        (throwable.statusCode.is5xxServerError || throwable.statusCode == HttpStatus.TOO_MANY_REQUESTS)) ||
                        throwable is java.io.IOException
                }
        )
}
