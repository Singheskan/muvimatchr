package org.example.muvimatchr.catalog.tmdb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// Transport DTO for TMDB's /genre/movie/list response. Tolerant of unknown upstream fields, same
// convention as TmdbMovie/TmdbDiscoverResponse.
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbGenreListResponse(val genres: List<TmdbGenre>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbGenre(val id: Int, val name: String)
