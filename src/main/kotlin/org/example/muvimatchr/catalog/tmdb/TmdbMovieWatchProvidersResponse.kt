package org.example.muvimatchr.catalog.tmdb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// Transport DTO for TMDB's /movie/{id}/watch/providers response. Unlike
// TmdbWatchProviderListResponse's flat `results` array (one region's reference catalogue),
// this endpoint's `results` is an object keyed by region code, each holding a `link` plus
// separate flatrate/rent/buy/ads provider arrays for that one region -- every region's
// availability data is returned in a single payload, so region selection is a response-reading
// concern (resolveRegionalAvailability), not a request-time query parameter.
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbMovieWatchProvidersResponse(
    val id: Long,
    val results: Map<String, TmdbRegionalAvailability> = emptyMap(),
)

// Nullable rather than defaulted-to-empty: TMDB omits a monetization category key entirely for
// a region rather than sending an empty array (confirmed live -- of one movie's ~126 regions,
// ~all omit `ads`, and roughly 40% omit `rent`/`buy` outright). Jackson does not apply a Kotlin
// constructor default when a JSON key is absent -- it passes null for the missing parameter,
// which throws for a non-nullable type ("Parameter specified as non-null is null") even though
// the parameter declares a default. A nullable type lets Jackson assign null directly with no
// constructor-default resolution involved; callers (resolveRegionalAvailability) treat null the
// same as empty via `?: emptyList()`.
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbRegionalAvailability(
    val link: String? = null,
    val flatrate: List<TmdbWatchProviderSummary>? = null,
    val rent: List<TmdbWatchProviderSummary>? = null,
    val buy: List<TmdbWatchProviderSummary>? = null,
    val ads: List<TmdbWatchProviderSummary>? = null,
)
