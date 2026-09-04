package org.example.muvimatchr.catalog

import kotlinx.coroutines.runBlocking
import org.example.muvimatchr.support.FAKE_TMDB_TOKEN
import org.example.muvimatchr.support.TmdbMockServerSupport
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

private const val MINIMAL_DISCOVER_FIXTURE = """
{"page": 1, "results": [], "total_results": 0, "total_pages": 0}
"""

class MovieCatalogClientTest : TmdbMockServerSupport() {

    @Autowired
    lateinit var movieCatalogClient: MovieCatalogClient

    @Test
    fun `discoverMovies sends the credential as a Bearer Authorization header`() {
        enqueueJson(MINIMAL_DISCOVER_FIXTURE)

        runBlocking { movieCatalogClient.discoverMovies(28, emptyList(), null) }

        val recorded = takeRecordedRequest()
        val authHeader = recorded.headers["Authorization"]
        assertTrue(authHeader != null && authHeader.startsWith("Bearer "), "expected Bearer scheme, got $authHeader")
        assertTrue(authHeader!!.endsWith(FAKE_TMDB_TOKEN), "expected header to end with the fake credential, got $authHeader")
    }

    @Test
    fun `discoverMovies request URL contains no credential`() {
        enqueueJson(MINIMAL_DISCOVER_FIXTURE)

        runBlocking { movieCatalogClient.discoverMovies(28, emptyList(), null) }

        val recorded = takeRecordedRequest()
        val requestLine = recorded.requestLine
        assertFalse(requestLine.contains(FAKE_TMDB_TOKEN), "credential leaked into request URL: $requestLine")
        assertFalse(requestLine.contains("api_key"), "api_key query parameter present in request URL: $requestLine")
    }

    @Test
    fun `discoverMovies with a genre and no providers sends with_genres and sort_by but no provider params`() {
        enqueueJson(MINIMAL_DISCOVER_FIXTURE)

        runBlocking { movieCatalogClient.discoverMovies(28, emptyList(), null) }

        val requestLine = takeRecordedRequest().requestLine
        assertTrue(requestLine.contains("with_genres=28"), requestLine)
        assertTrue(requestLine.contains("sort_by=popularity.desc"), requestLine)
        assertFalse(requestLine.contains("watch_region"), requestLine)
        assertFalse(requestLine.contains("with_watch_providers"), requestLine)
    }

    @Test
    fun `discoverMovies with no genre sends sort_by but no with_genres`() {
        enqueueJson(MINIMAL_DISCOVER_FIXTURE)

        runBlocking { movieCatalogClient.discoverMovies(null, emptyList(), null) }

        val requestLine = takeRecordedRequest().requestLine
        assertTrue(requestLine.contains("sort_by=popularity.desc"), requestLine)
        assertFalse(requestLine.contains("with_genres"), requestLine)
    }
}
