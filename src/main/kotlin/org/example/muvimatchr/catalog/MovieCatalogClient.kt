package org.example.muvimatchr.catalog

import kotlinx.coroutines.reactor.awaitSingle
import org.example.muvimatchr.catalog.tmdb.TmdbDiscoverResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
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
            .retryWhen(
                Retry.backoff(RETRY_MAX_ATTEMPTS, RETRY_MIN_BACKOFF)
                    .maxBackoff(RETRY_MAX_BACKOFF)
                    .jitter(RETRY_JITTER)
                    .filter { throwable ->
                        throwable is WebClientResponseException &&
                            (throwable.statusCode.is5xxServerError || throwable.statusCode == HttpStatus.TOO_MANY_REQUESTS)
                    }
            )
            .awaitSingle()
}
