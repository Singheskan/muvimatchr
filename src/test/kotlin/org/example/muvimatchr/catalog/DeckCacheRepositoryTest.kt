package org.example.muvimatchr.catalog

import org.example.muvimatchr.support.PostgresTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

class DeckCacheRepositoryTest : PostgresTestSupport() {

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

    private fun totalResultsForKey(cacheKey: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT total_results FROM deck_cache_entry WHERE cache_key = ?",
            Int::class.java,
            cacheKey,
        )!!

    @Test
    fun `upsertDeck for a new cache key inserts exactly one row`() {
        val key = buildDeckCacheKey(28, emptyList(), null)

        deckCacheRepository.upsertDeck(UUID.randomUUID(), key, """[{"tmdbId":1}]""", 1, Instant.now())

        assertEquals(1, rowCountForKey(key))
    }

    @Test
    fun `upsertDeck twice with the same cache key leaves exactly one row carrying the second call's payload`() {
        val key = buildDeckCacheKey(35, emptyList(), null)

        deckCacheRepository.upsertDeck(UUID.randomUUID(), key, """[{"tmdbId":1}]""", 1, Instant.now())
        deckCacheRepository.upsertDeck(UUID.randomUUID(), key, """[{"tmdbId":1},{"tmdbId":2}]""", 2, Instant.now())

        assertEquals(1, rowCountForKey(key))
        assertEquals(2, totalResultsForKey(key))
    }

    @Test
    fun `upsertDeck twice with the same cache key raises no DataIntegrityViolationException`() {
        val key = buildDeckCacheKey(12, emptyList(), null)

        assertDoesNotThrow {
            deckCacheRepository.upsertDeck(UUID.randomUUID(), key, """[]""", 0, Instant.now())
            deckCacheRepository.upsertDeck(UUID.randomUUID(), key, """[]""", 0, Instant.now())
        }
    }

    @Test
    fun `two different cache keys yield two distinct rows`() {
        val keyA = buildDeckCacheKey(28, emptyList(), null)
        val keyB = buildDeckCacheKey(35, emptyList(), null)

        deckCacheRepository.upsertDeck(UUID.randomUUID(), keyA, """[]""", 0, Instant.now())
        deckCacheRepository.upsertDeck(UUID.randomUUID(), keyB, """[]""", 0, Instant.now())

        assertEquals(1, rowCountForKey(keyA))
        assertEquals(1, rowCountForKey(keyB))
    }
}
