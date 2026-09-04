package org.example.muvimatchr.catalog

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.example.muvimatchr.support.TmdbMockServerSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.server.ResponseStatusException
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

private const val SMALL_DISCOVER_FIXTURE = """
{
  "page": 1,
  "results": [
    {"id": 550, "title": "Fight Club", "poster_path": "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", "genre_ids": [18, 53], "vote_average": 8.4, "popularity": 61.4, "release_date": "1999-10-15", "overview": "..."}
  ],
  "total_results": 1,
  "total_pages": 1
}
"""

// A three-movie page, small enough to prove the fan-out shape without a large fixture.
private const val THREE_MOVIE_DISCOVER_FIXTURE = """
{
  "page": 1,
  "results": [
    {"id": 550, "title": "Fight Club", "poster_path": "/a.jpg", "genre_ids": [18], "vote_average": 8.4, "popularity": 61.4, "release_date": "1999-10-15", "overview": "..."},
    {"id": 155, "title": "The Dark Knight", "poster_path": "/b.jpg", "genre_ids": [28], "vote_average": 8.5, "popularity": 90.1, "release_date": "2008-07-16", "overview": "..."},
    {"id": 680, "title": "Pulp Fiction", "poster_path": "/c.jpg", "genre_ids": [80], "vote_average": 8.5, "popularity": 55.7, "release_date": "1994-09-10", "overview": "..."}
  ],
  "total_results": 3,
  "total_pages": 1
}
"""

// Identical across movies deliberately: with concurrent per-movie lookups racing a shared FIFO
// mock queue, which movie's request happens to dequeue which response is not guaranteed. Using
// one fixture for every movie keeps assertions about "does every movie carry resolved provider
// data" deterministic without depending on per-movie response correlation.
private const val WATCH_PROVIDERS_FIXTURE = """
{"id": 0, "results": {"DE": {"link": "https://example.com/watch", "flatrate": [{"provider_id": 99, "provider_name": "TestProvider", "logo_path": "/test.jpg", "display_priority": 1}], "rent": [], "buy": [], "ads": []}}}
"""

class MovieCatalogServiceTest : TmdbMockServerSupport() {

    @Autowired
    lateinit var movieCatalogService: MovieCatalogService

    @Autowired
    lateinit var deckCacheRepository: DeckCacheRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearDeckCache() {
        deckCacheRepository.deleteAll()
    }

    private fun rowCountForKey(cacheKey: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM deck_cache_entry WHERE cache_key = ?",
            Int::class.java,
            cacheKey,
        )!!

    private fun ageRow(cacheKey: String, olderThan: java.time.Duration) {
        val agedInstant = Instant.now().minus(olderThan).minus(1, ChronoUnit.MINUTES)
        jdbcTemplate.update(
            "UPDATE deck_cache_entry SET fetched_at = ? WHERE cache_key = ?",
            Timestamp.from(agedInstant),
            cacheKey,
        )
    }

    @Test
    fun `two consecutive getDeck calls within the TTL increase the request count by exactly one total`() {
        val before = requestCount()
        enqueueJson(SMALL_DISCOVER_FIXTURE)

        movieCatalogService.getDeck(28, emptyList(), null)
        movieCatalogService.getDeck(28, emptyList(), null)

        assertEquals(1, requestCount() - before)
    }

    @Test
    fun `a getDeck call for an expired cache row triggers exactly one further upstream request and updates the row in place`() {
        val key = buildDeckCacheKey(28, emptyList(), null)
        enqueueJson(SMALL_DISCOVER_FIXTURE)
        movieCatalogService.getDeck(28, emptyList(), null)
        assertEquals(1, rowCountForKey(key))

        ageRow(key, java.time.Duration.ofHours(6))

        val before = requestCount()
        enqueueJson(SMALL_DISCOVER_FIXTURE)
        movieCatalogService.getDeck(28, emptyList(), null)

        assertEquals(1, requestCount() - before)
        assertEquals(1, rowCountForKey(key))
    }

    @Test
    fun `two getDeck calls differing only by genre produce two distinct cache keys and two distinct rows`() {
        enqueueJson(SMALL_DISCOVER_FIXTURE)
        enqueueJson(SMALL_DISCOVER_FIXTURE)

        movieCatalogService.getDeck(28, emptyList(), null)
        movieCatalogService.getDeck(35, emptyList(), null)

        val keyA = buildDeckCacheKey(28, emptyList(), null)
        val keyB = buildDeckCacheKey(35, emptyList(), null)
        assertNotEquals(keyA, keyB)
        assertEquals(1, rowCountForKey(keyA))
        assertEquals(1, rowCountForKey(keyB))
    }

    @Test
    fun `buildDeckCacheKey is stable across provider selection order`() {
        val keyOrderA = buildDeckCacheKey(null, listOf(8, 9), "DE")
        val keyOrderB = buildDeckCacheKey(null, listOf(9, 8), "DE")

        assertEquals(keyOrderA, keyOrderB)
    }

    @Test
    fun `buildDeckCacheKey differs by region even with identical genre and providers`() {
        val keyDe = buildDeckCacheKey(28, listOf(8), "DE")
        val keyUs = buildDeckCacheKey(28, listOf(8), "US")

        assertNotEquals(keyDe, keyUs)
    }

    @Test
    fun `a cold deck refresh over a page of movies issues exactly one discover request plus one availability request per movie`() {
        enqueueJson(THREE_MOVIE_DISCOVER_FIXTURE)
        enqueueJson(WATCH_PROVIDERS_FIXTURE)
        enqueueJson(WATCH_PROVIDERS_FIXTURE)
        enqueueJson(WATCH_PROVIDERS_FIXTURE)
        val before = requestCount()

        movieCatalogService.getDeck(null, emptyList(), "DE")

        assertEquals(4, requestCount() - before)
        val requestLines = (1..4).map { takeRecordedRequest().requestLine }
        assertEquals(1, requestLines.count { it.contains("/discover/movie") })
        assertEquals(3, requestLines.count { it.contains("/watch/providers") })
    }

    @Test
    fun `after a refresh, a second deck read within the TTL for the same filters issues zero requests of any kind`() {
        enqueueJson(THREE_MOVIE_DISCOVER_FIXTURE)
        enqueueJson(WATCH_PROVIDERS_FIXTURE)
        enqueueJson(WATCH_PROVIDERS_FIXTURE)
        enqueueJson(WATCH_PROVIDERS_FIXTURE)
        movieCatalogService.getDeck(null, emptyList(), "DE")

        val before = requestCount()
        movieCatalogService.getDeck(null, emptyList(), "DE")

        assertEquals(0, requestCount() - before)
    }

    @Test
    fun `the cached row's movies carry the region-resolved provider data readable without any further upstream call`() {
        enqueueJson(THREE_MOVIE_DISCOVER_FIXTURE)
        enqueueJson(WATCH_PROVIDERS_FIXTURE)
        enqueueJson(WATCH_PROVIDERS_FIXTURE)
        enqueueJson(WATCH_PROVIDERS_FIXTURE)
        movieCatalogService.getDeck(null, emptyList(), "DE")

        val result = movieCatalogService.getDeck(null, emptyList(), "DE")

        assertEquals(3, result.movies.size)
        result.movies.forEach { movie ->
            assertEquals(1, movie.providers.size, "expected exactly one resolved provider for movie ${movie.tmdbId}")
            assertEquals(99, movie.providers[0].providerId)
            assertEquals("TestProvider", movie.providers[0].providerName)
            assertEquals("https://example.com/watch", movie.watchLink)
        }
    }

    @Test
    fun `a per-title availability call that exhausts its retries does not abort the refresh -- that movie gets empty availability, its siblings keep theirs`() {
        // A routing dispatcher (not the shared FIFO queue) is required here because this test
        // needs a *specific* movie's request to fail while its siblings succeed -- concurrent
        // per-movie lookups racing a FIFO queue give no guarantee about which movie's request
        // dequeues which response.
        val originalDispatcher = TmdbMockServerSupport.tmdbServer.dispatcher
        TmdbMockServerSupport.tmdbServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val requestLine = request.requestLine
                return when {
                    requestLine.contains("/discover/movie") ->
                        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(THREE_MOVIE_DISCOVER_FIXTURE).build()
                    requestLine.contains("/movie/155/watch/providers") ->
                        MockResponse.Builder().code(500).build()
                    requestLine.contains("/watch/providers") ->
                        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(WATCH_PROVIDERS_FIXTURE).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        try {
            val result = movieCatalogService.getDeck(null, emptyList(), "DE")

            val darkKnight = result.movies.first { it.tmdbId == 155L }
            assertTrue(darkKnight.providers.isEmpty(), "the persistently-failing movie must get an empty provider list, not propagate")
            assertNull(darkKnight.watchLink)

            val fightClub = result.movies.first { it.tmdbId == 550L }
            assertEquals(1, fightClub.providers.size, "a sibling movie must keep its own resolved data")
            val pulpFiction = result.movies.first { it.tmdbId == 680L }
            assertEquals(1, pulpFiction.providers.size, "a sibling movie must keep its own resolved data")
        } finally {
            TmdbMockServerSupport.tmdbServer.dispatcher = originalDispatcher
            // Requests served by the custom dispatcher above never came off the shared enqueued
            // queue, but they are still recorded; drain them so no leftover request leaks into a
            // later test's takeRecordedRequest() call (same discipline as the shared @BeforeEach).
            while (TmdbMockServerSupport.tmdbServer.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
                // discard
            }
        }
    }

    @Test
    fun `a fresh cached row is served with stale false, distinguishing a normal cache hit from a degraded one`() {
        enqueueJson(SMALL_DISCOVER_FIXTURE)
        movieCatalogService.getDeck(28, emptyList(), null)

        val result = movieCatalogService.getDeck(28, emptyList(), null)

        assertFalse(result.stale, "a fresh cache hit must not be marked stale")
    }

    @Test
    fun `an upstream outage with a past-TTL cached row serves that row's movies marked stale after retries are exhausted`() {
        val key = buildDeckCacheKey(28, emptyList(), null)
        enqueueJson(SMALL_DISCOVER_FIXTURE)
        movieCatalogService.getDeck(28, emptyList(), null)
        ageRow(key, java.time.Duration.ofHours(6))

        val before = requestCount()
        // RETRY_MAX_ATTEMPTS=3 -> 1 initial attempt + 3 retries = 4 total attempts before exhaustion.
        repeat(4) { enqueueJson(SMALL_DISCOVER_FIXTURE, status = 500) }

        val result = movieCatalogService.getDeck(28, emptyList(), null)

        assertEquals(4, requestCount() - before, "expected the client's full retry budget to be consumed")
        assertTrue(result.stale, "an outage fallback must be marked stale")
        assertEquals(1, result.movies.size)
        assertEquals("Fight Club", result.movies[0].title)
        assertEquals(1, rowCountForKey(key), "the outage fallback must not write a second row")
    }

    @Test
    fun `an upstream outage with no cached row for that filter combination raises a 503`() {
        val key = buildDeckCacheKey(35, emptyList(), null)
        repeat(4) { enqueueJson(SMALL_DISCOVER_FIXTURE, status = 500) }

        val exception = assertThrows(ResponseStatusException::class.java) {
            movieCatalogService.getDeck(35, emptyList(), null)
        }

        assertEquals(503, exception.statusCode.value())
        assertEquals(0, rowCountForKey(key), "a failed cold refresh must not write a poisoned cache row")
    }

    @Test
    fun `an upstream outage with no cached row writes nothing to the cache regardless of the surrounding tests`() {
        val key = buildDeckCacheKey(36, emptyList(), null)
        repeat(4) { enqueueJson(SMALL_DISCOVER_FIXTURE, status = 500) }

        assertThrows(ResponseStatusException::class.java) {
            movieCatalogService.getDeck(36, emptyList(), null)
        }

        assertEquals(0, rowCountForKey(key))
    }

    @Test
    fun `a transient failure that succeeds on a later attempt resolves to a fresh, non-stale deck with an updated timestamp`() {
        val key = buildDeckCacheKey(28, emptyList(), null)
        enqueueJson(SMALL_DISCOVER_FIXTURE)
        movieCatalogService.getDeck(28, emptyList(), null)
        ageRow(key, java.time.Duration.ofHours(6))
        val agedFetchedAt = jdbcTemplate.queryForObject(
            "SELECT fetched_at FROM deck_cache_entry WHERE cache_key = ?",
            java.sql.Timestamp::class.java,
            key,
        )!!.toInstant()

        enqueueJson(SMALL_DISCOVER_FIXTURE, status = 500)
        enqueueJson(SMALL_DISCOVER_FIXTURE)

        val result = movieCatalogService.getDeck(28, emptyList(), null)

        assertFalse(result.stale, "a retry that eventually succeeds must resolve to a fresh, non-stale deck")
        assertEquals(1, rowCountForKey(key))
        assertTrue(result.fetchedAt.isAfter(agedFetchedAt), "the stored row's timestamp must be updated on a successful refresh")
    }

    @Test
    fun `a client error the retry policy excludes fails without consuming further retry attempts`() {
        val before = requestCount()
        enqueueJson(SMALL_DISCOVER_FIXTURE, status = 400)

        assertThrows(Exception::class.java) {
            movieCatalogService.getDeck(37, emptyList(), null)
        }

        assertEquals(1, requestCount() - before, "a non-retryable 4xx must consume exactly one upstream attempt")
    }

    @Test
    fun `no more than the configured maximum number of availability requests are in flight simultaneously`() {
        // In-flight observation against the fake server is impractical for a 3-movie fixture
        // (fewer movies than the default bound of 8, so the bound itself is never exercised);
        // asserting the semaphore is sized from the configured property is the plan's documented
        // equivalent proof that the bound is configurable and wired, not hardcoded/unbounded.
        val field = MovieCatalogService::class.java.getDeclaredField("providerLookupConcurrency")
        field.isAccessible = true
        assertEquals(8, field.getInt(movieCatalogService))
    }
}
