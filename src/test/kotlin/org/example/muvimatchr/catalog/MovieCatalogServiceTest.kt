package org.example.muvimatchr.catalog

import org.example.muvimatchr.support.TmdbMockServerSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

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
}
