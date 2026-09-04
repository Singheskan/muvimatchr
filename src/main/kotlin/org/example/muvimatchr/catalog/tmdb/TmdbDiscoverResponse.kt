package org.example.muvimatchr.catalog.tmdb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// Transport DTO for TMDB's /discover/movie response envelope. Tolerant of unknown upstream
// fields so a new TMDB field never breaks deserialization.
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbDiscoverResponse(
    val page: Int,
    val results: List<TmdbMovie>,
    @JsonProperty("total_results") val totalResults: Int,
    @JsonProperty("total_pages") val totalPages: Int,
)
