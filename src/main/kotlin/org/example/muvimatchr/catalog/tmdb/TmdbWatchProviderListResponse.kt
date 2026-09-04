package org.example.muvimatchr.catalog.tmdb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// Transport DTO for TMDB's /watch/providers/movie response. Unlike the per-movie
// /movie/{id}/watch/providers endpoint (whose `results` is keyed by region), this endpoint's
// `results` is a flat array of provider summaries for the region passed via watch_region.
// Store logoPath exactly as returned (a leading-slash relative path), never an absolute URL --
// same convention as TmdbMovie.posterPath.
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbWatchProviderListResponse(val results: List<TmdbWatchProviderSummary>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbWatchProviderSummary(
    @JsonProperty("provider_id") val providerId: Int,
    @JsonProperty("provider_name") val providerName: String,
    @JsonProperty("logo_path") val logoPath: String?,
    @JsonProperty("display_priority") val displayPriority: Int?,
)
