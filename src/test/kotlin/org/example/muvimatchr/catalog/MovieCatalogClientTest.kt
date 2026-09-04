package org.example.muvimatchr.catalog

import kotlinx.coroutines.runBlocking
import org.example.muvimatchr.support.FAKE_TMDB_TOKEN
import org.example.muvimatchr.support.TmdbMockServerSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

private const val MINIMAL_DISCOVER_FIXTURE = """
{"page": 1, "results": [], "total_results": 0, "total_pages": 0}
"""

// Two distinct regions with different provider sets -- catches a wrong-region resolution bug
// that would otherwise pass by coincidence against a fixture with only one region.
private const val MOVIE_WATCH_PROVIDERS_FIXTURE = """
{
  "id": 550,
  "results": {
    "DE": {
      "link": "https://www.themoviedb.org/movie/550-fight-club/watch?locale=DE",
      "flatrate": [ {"provider_id": 8, "provider_name": "Netflix", "logo_path": "/netflix.jpg", "display_priority": 1} ],
      "rent": [ {"provider_id": 2, "provider_name": "Apple TV", "logo_path": "/apple.jpg", "display_priority": 2} ],
      "buy": [],
      "ads": []
    },
    "US": {
      "link": "https://www.themoviedb.org/movie/550-fight-club/watch?locale=US",
      "flatrate": [ {"provider_id": 337, "provider_name": "Disney Plus", "logo_path": "/disney.jpg", "display_priority": 1} ],
      "rent": [],
      "buy": [],
      "ads": []
    }
  }
}
"""

// A duplicate provider id (8) appears in both flatrate and rent for DE -- proves the merge
// collapses duplicates and keeps the first occurrence's name/logo rather than the later one's.
private const val MOVIE_WATCH_PROVIDERS_DEDUP_FIXTURE = """
{
  "id": 550,
  "results": {
    "DE": {
      "link": "https://www.themoviedb.org/movie/550-fight-club/watch?locale=DE",
      "flatrate": [ {"provider_id": 8, "provider_name": "Netflix", "logo_path": "/netflix.jpg", "display_priority": 1} ],
      "rent": [
        {"provider_id": 8, "provider_name": "Netflix Rent Duplicate", "logo_path": "/dup.jpg", "display_priority": 5},
        {"provider_id": 2, "provider_name": "Apple TV", "logo_path": "/apple.jpg", "display_priority": 2}
      ],
      "buy": [],
      "ads": []
    }
  }
}
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

    @Test
    fun `resolving a multi-region payload for DE returns only DE's providers`() {
        enqueueJson(MOVIE_WATCH_PROVIDERS_FIXTURE)
        val response = runBlocking { movieCatalogClient.fetchMovieWatchProviders(550) }
        takeRecordedRequest()

        val resolved = resolveRegionalAvailability(response, "DE")

        assertEquals(2, resolved.providers.size)
        assertTrue(resolved.providers.any { it.providerId == 8 && it.providerName == "Netflix" })
        assertTrue(resolved.providers.any { it.providerId == 2 && it.providerName == "Apple TV" })
    }

    @Test
    fun `resolving the same multi-region payload for US returns US's own, different providers`() {
        enqueueJson(MOVIE_WATCH_PROVIDERS_FIXTURE)
        val response = runBlocking { movieCatalogClient.fetchMovieWatchProviders(550) }
        takeRecordedRequest()

        val resolved = resolveRegionalAvailability(response, "US")

        assertEquals(1, resolved.providers.size)
        assertTrue(resolved.providers.any { it.providerId == 337 && it.providerName == "Disney Plus" })
        assertFalse(resolved.providers.any { it.providerId == 8 }, "US result must not carry DE's Netflix entry")
    }

    @Test
    fun `resolving for a region absent from the payload returns an empty list and a null link, never another region's data`() {
        enqueueJson(MOVIE_WATCH_PROVIDERS_FIXTURE)
        val response = runBlocking { movieCatalogClient.fetchMovieWatchProviders(550) }
        takeRecordedRequest()

        val resolved = resolveRegionalAvailability(response, "FR")

        assertTrue(resolved.providers.isEmpty())
        assertEquals(null, resolved.watchLink)
    }

    @Test
    fun `resolving a region merges flatrate, rent, buy and ads into one deduplicated list keeping the first occurrence`() {
        enqueueJson(MOVIE_WATCH_PROVIDERS_DEDUP_FIXTURE)
        val response = runBlocking { movieCatalogClient.fetchMovieWatchProviders(550) }
        takeRecordedRequest()

        val resolved = resolveRegionalAvailability(response, "DE")

        assertEquals(2, resolved.providers.size)
        val netflix = resolved.providers.first { it.providerId == 8 }
        assertEquals("Netflix", netflix.providerName)
        assertEquals("/netflix.jpg", netflix.logoPath)
    }

    @Test
    fun `resolved watch link is the requested region's own link value`() {
        enqueueJson(MOVIE_WATCH_PROVIDERS_FIXTURE)
        val response = runBlocking { movieCatalogClient.fetchMovieWatchProviders(550) }
        takeRecordedRequest()

        val resolvedDe = resolveRegionalAvailability(response, "DE")
        val resolvedUs = resolveRegionalAvailability(response, "US")

        assertEquals("https://www.themoviedb.org/movie/550-fight-club/watch?locale=DE", resolvedDe.watchLink)
        assertEquals("https://www.themoviedb.org/movie/550-fight-club/watch?locale=US", resolvedUs.watchLink)
    }

    @Test
    fun `fetchMovieWatchProviders targets the per-title endpoint for the requested movie id and carries no region query parameter`() {
        enqueueJson(MOVIE_WATCH_PROVIDERS_FIXTURE)

        runBlocking { movieCatalogClient.fetchMovieWatchProviders(550) }

        val requestLine = takeRecordedRequest().requestLine
        assertTrue(requestLine.contains("/movie/550/watch/providers"), requestLine)
        assertFalse(requestLine.contains("region"), requestLine)
    }
}
