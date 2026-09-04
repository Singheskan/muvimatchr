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

// Every list defaults to empty so a region entry that omits a monetization category (e.g. a
// region with only `flatrate` and no `rent`/`buy`/`ads`) deserializes cleanly rather than
// requiring every category to be present in the upstream payload.
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbRegionalAvailability(
    val link: String? = null,
    val flatrate: List<TmdbWatchProviderSummary> = emptyList(),
    val rent: List<TmdbWatchProviderSummary> = emptyList(),
    val buy: List<TmdbWatchProviderSummary> = emptyList(),
    val ads: List<TmdbWatchProviderSummary> = emptyList(),
)
