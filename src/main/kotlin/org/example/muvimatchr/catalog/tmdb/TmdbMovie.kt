package org.example.muvimatchr.catalog.tmdb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// Transport DTO for one movie in a TMDB /discover/movie response. A data class, not a JPA
// entity — the plain-class rule applies only to @Entity types. Store posterPath exactly as
// TMDB returns it (a leading-slash relative path), never a composed absolute image URL: the
// base URL and available sizes come from /configuration, which this phase does not call
// (COVERAGE.md OPT-OUT) — composing a sized URL is Phase 6's job.
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbMovie(
    val id: Long,
    val title: String,
    @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("genre_ids") val genreIds: List<Int>,
    @JsonProperty("vote_average") val voteAverage: Double,
    val popularity: Double,
    @JsonProperty("release_date") val releaseDate: String?,
    val overview: String?,
)
